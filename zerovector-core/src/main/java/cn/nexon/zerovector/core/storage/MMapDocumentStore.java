/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于内存映射文件的追加式正文存储。
 *
 * <p>使用 JDK 25 的 Foreign Function &amp; Memory API（{@link Arena}/{@link MemorySegment}）
 * 管理映射生命周期：关闭 {@link Arena} 即确定性解除映射，不依赖反射或 GC，
 * 因此 Windows 上也能安全地压缩、替换和删除数据文件。
 *
 * <p><b>容量限制</b>：单个数据文件最大 2GB（{@link Integer#MAX_VALUE}），
 * 超出前应通过 {@link ShardedMMapStore} 分片。
 */
public final class MMapDocumentStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MMapDocumentStore.class);
    private static final int INITIAL_SIZE = 1024 * 1024;
    private static final int MAX_SIZE = Integer.MAX_VALUE - 8;
    private static final String INDEX_HEADER = "# zerovector mmap index v1";

    private final String dataFilePath;
    private final String indexFilePath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, FileLocation> index = new ConcurrentHashMap<>();

    private FileChannel channel;
    private Mapping mapping;
    private long writePosition;
    private boolean closed;

    public record FileLocation(long offset, int length) {
    }

    private record Mapping(MemorySegment segment, Arena arena, long size) {
    }

    private MMapDocumentStore(FileChannel channel, String dataFilePath, String indexFilePath) {
        this.channel = channel;
        this.dataFilePath = dataFilePath;
        this.indexFilePath = indexFilePath;
    }

    public static MMapDocumentStore open(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            MMapDocumentStore store = new MMapDocumentStore(channel,
                    path.toAbsolutePath().toString(),
                    path.toAbsolutePath() + ".index");
            store.loadIndex();
            store.openMapping();
            return store;
        } catch (IOException e) {
            closeQuietly(channel);
            throw new StorageException(filePath, "open", StorageException.ERROR_CODE_IO_ERROR,
                    "Failed to open document store", e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    private void openMapping() throws IOException {
        long fileSize = channel.size();
        if (fileSize > MAX_SIZE) {
            throw new StorageException(dataFilePath, "open", StorageException.ERROR_CODE_FILE_CORRUPT,
                    "Data file exceeds 2GB limit; use ShardedMMapStore instead");
        }
        long used = usedBytes();
        if (used > MAX_SIZE) {
            throw new StorageException(dataFilePath, "open", StorageException.ERROR_CODE_INDEX_CORRUPT,
                    "Data file exceeds 2GB limit; use ShardedMMapStore instead");
        }
        if (used > fileSize) {
            throw new StorageException(dataFilePath, "open", StorageException.ERROR_CODE_INDEX_CORRUPT,
                    "Index points beyond end of data file (used=" + used + ", size=" + fileSize + ")");
        }
        long size = Math.max(Math.max(fileSize, used), INITIAL_SIZE);
        resizeFile(channel, size);
        this.mapping = map(size);
        this.writePosition = used;
    }

    private Mapping map(long size) throws IOException {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
            return new Mapping(segment, arena, size);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /** 根据索引计算已使用字节数；重启后从该位置继续追加，避免覆盖旧数据。 */
    private long usedBytes() {
        long used = 0;
        for (FileLocation location : index.values()) {
            used = Math.max(used, location.offset() + Integer.BYTES + location.length());
        }
        return used;
    }

    public FileLocation addChunk(String chunkId, String content) throws IOException {
        if (chunkId == null || chunkId.isEmpty()) {
            throw new IllegalArgumentException("chunkId must not be empty");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        lock.writeLock().lock();
        try {
            ensureOpen();
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            long required = writePosition + Integer.BYTES + bytes.length;
            if (required > MAX_SIZE) {
                throw new StorageException(dataFilePath, "addChunk",
                        StorageException.ERROR_CODE_BUFFER_OVERFLOW,
                        "Single data file cannot exceed 2GB; increase mmap shard count");
            }
            if (required > mapping.size()) {
                grow(Math.max(mapping.size() * 2, required));
            }

            long offset = writePosition;
            mapping.segment().set(ValueLayout.JAVA_INT_UNALIGNED, offset, bytes.length);
            MemorySegment.copy(bytes, 0, mapping.segment(), ValueLayout.JAVA_BYTE, offset + Integer.BYTES,
                    bytes.length);
            writePosition += Integer.BYTES + bytes.length;

            FileLocation location = new FileLocation(offset, bytes.length);
            index.put(chunkId, location);
            return location;
        } catch (IOException e) {
            throw new StorageException(dataFilePath, "addChunk", StorageException.ERROR_CODE_IO_ERROR,
                    "Failed to add chunk to store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void grow(long newSize) throws IOException {
        if (newSize > MAX_SIZE) {
            throw new StorageException(dataFilePath, "grow", StorageException.ERROR_CODE_BUFFER_OVERFLOW,
                    "Requested mapping exceeds 2GB limit: " + newSize);
        }
        long target = Math.min(MAX_SIZE, newSize);
        resizeFile(channel, target);

        Mapping old = mapping;
        mapping = map(target);
        if (old != null) {
            old.arena().close();
        }
        logger.debug("mmap 文件扩容至 {} MB: {}", target / (1024 * 1024), dataFilePath);
    }

    public String getChunk(String chunkId) {
        lock.readLock().lock();
        try {
            if (closed || chunkId == null || chunkId.isEmpty()) {
                return null;
            }
            FileLocation location = index.get(chunkId);
            if (location == null) {
                return null;
            }
            if (!isValid(location)) {
                throw new StorageException(dataFilePath, "getChunk",
                        StorageException.ERROR_CODE_INDEX_CORRUPT,
                        "Index entry out of bounds for chunk " + chunkId);
            }

            int storedLength = mapping.segment().get(ValueLayout.JAVA_INT_UNALIGNED, location.offset());
            if (storedLength != location.length()) {
                throw new StorageException(dataFilePath, "getChunk",
                        StorageException.ERROR_CODE_INDEX_CORRUPT,
                        "Index length mismatch for chunk " + chunkId);
            }

            byte[] bytes = new byte[location.length()];
            MemorySegment.copy(mapping.segment(), ValueLayout.JAVA_BYTE,
                    location.offset() + Integer.BYTES, bytes, 0, location.length());
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean isValid(FileLocation location) {
        return location.offset() >= 0
                && location.length() >= 0
                && location.offset() + Integer.BYTES + location.length() <= mapping.size();
    }

    public boolean deleteChunk(String chunkId) {
        lock.writeLock().lock();
        try {
            return chunkId != null && index.remove(chunkId) != null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean containsChunk(String chunkId) {
        return chunkId != null && index.containsKey(chunkId);
    }

    public Set<String> getAllChunkIds() {
        return Set.copyOf(index.keySet());
    }

    /**
     * 回收已删除数据占用的空间。
     * <p>先解除旧映射再移动文件，保证 Windows 上也能正确替换。
     */
    public void compact() throws IOException {
        lock.writeLock().lock();
        try {
            ensureOpen();
            Path dataPath = Paths.get(dataFilePath);
            Path tempPath = Paths.get(dataFilePath + ".tmp");
            Path backupPath = Paths.get(dataFilePath + ".old");

            Map<String, FileLocation> newIndex = writeCompactedData(tempPath);
            long newSize = Math.max(INITIAL_SIZE, usedBytes(newIndex));

            // 解除旧映射、关闭句柄后才能安全替换文件
            mapping.arena().close();
            mapping = null;
            channel.close();

            Files.move(dataPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tempPath, dataPath, StandardCopyOption.REPLACE_EXISTING);
                channel = FileChannel.open(dataPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
                resizeFile(channel, newSize);
                mapping = map(newSize);
                writePosition = usedBytes(newIndex);
                index.clear();
                index.putAll(newIndex);
                Files.deleteIfExists(backupPath);
                saveIndex();
                channel.force(true);
                logger.debug("存储压缩完成: {}", dataFilePath);
            } catch (IOException | RuntimeException e) {
                Files.move(backupPath, dataPath, StandardCopyOption.REPLACE_EXISTING);
                channel = FileChannel.open(dataPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
                mapping = map(Math.max(INITIAL_SIZE, channel.size()));
                writePosition = usedBytes();
                throw e;
            } finally {
                Files.deleteIfExists(tempPath);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Map<String, FileLocation> writeCompactedData(Path tempPath) throws IOException {
        Map<String, FileLocation> newIndex = new ConcurrentHashMap<>();
        try (OutputStream raw = Files.newOutputStream(tempPath, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             BufferedOutputStream out = new BufferedOutputStream(raw)) {
            long offset = 0;
            for (Map.Entry<String, FileLocation> entry : index.entrySet()) {
                FileLocation location = entry.getValue();
                if (!isValid(location)) {
                    throw new StorageException(dataFilePath, "compact",
                            StorageException.ERROR_CODE_INDEX_CORRUPT,
                            "Index entry out of bounds for chunk " + entry.getKey());
                }
                out.write(intToBytes(location.length()));
                byte[] bytes = new byte[location.length()];
                MemorySegment.copy(mapping.segment(), ValueLayout.JAVA_BYTE,
                        location.offset() + Integer.BYTES, bytes, 0, location.length());
                out.write(bytes);

                newIndex.put(entry.getKey(), new FileLocation(offset, location.length()));
                offset += Integer.BYTES + location.length();
                if (offset > MAX_SIZE) {
                    throw new StorageException(dataFilePath, "compact",
                            StorageException.ERROR_CODE_BUFFER_OVERFLOW,
                            "Compacted file exceeds 2GB limit");
                }
            }
        }
        try (FileChannel tempChannel = FileChannel.open(tempPath, StandardOpenOption.WRITE)) {
            tempChannel.force(true);
        }
        return newIndex;
    }

    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(java.nio.ByteOrder.nativeOrder())
                .putInt(value)
                .array();
    }

    private static long usedBytes(Map<String, FileLocation> index) {
        long used = 0;
        for (FileLocation location : index.values()) {
            used = Math.max(used, location.offset() + Integer.BYTES + location.length());
        }
        return used;
    }

    /** 清空数据并重置文件大小，用于 {@code ChunkStorage.clear()}。 */
    public void clear() throws IOException {
        lock.writeLock().lock();
        try {
            ensureOpen();
            index.clear();

            // Windows 上必须先解除旧映射，才能截断被映射的文件
            Mapping old = mapping;
            if (old != null) {
                old.arena().close();
                mapping = null;
            }
            resizeFile(channel, INITIAL_SIZE);
            mapping = map(INITIAL_SIZE);
            writePosition = 0;
            saveIndex();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void resizeFile(FileChannel channel, long size) throws IOException {
        long current = channel.size();
        if (current == size) {
            return;
        }
        if (size < current) {
            channel.truncate(size);
        } else {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    public String getUsageInfo() {
        lock.readLock().lock();
        try {
            if (closed || mapping == null) {
                return "closed";
            }
            long used = writePosition;
            long total = mapping.size();
            return String.format("%d/%d bytes (%.2f%%)", used, total,
                    total == 0 ? 0.0 : (double) used / total * 100);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void loadIndex() {
        Path indexPath = Paths.get(indexFilePath);
        if (!Files.exists(indexPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length != 3) {
                    throw new StorageException(indexFilePath, "loadIndex",
                            StorageException.ERROR_CODE_INDEX_CORRUPT, "Malformed index line: " + line);
                }
                String chunkId = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                long offset = Long.parseLong(parts[1]);
                int length = Integer.parseInt(parts[2]);
                index.put(chunkId, new FileLocation(offset, length));
            }
            logger.debug("已加载 {} 个文档块索引", index.size());
        } catch (IOException | IllegalArgumentException e) {
            throw new StorageException(indexFilePath, "loadIndex",
                    StorageException.ERROR_CODE_INDEX_CORRUPT, "Failed to parse index file", e);
        }
    }

    public void saveIndex() {
        lock.readLock().lock();
        try {
            Path indexPath = Paths.get(indexFilePath);
            Path tempPath = Paths.get(indexFilePath + ".tmp");

            List<String> lines = new ArrayList<>(index.size() + 1);
            lines.add(INDEX_HEADER);
            for (Map.Entry<String, FileLocation> entry : index.entrySet()) {
                String encodedId = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
                lines.add(encodedId + "\t" + entry.getValue().offset() + "\t" + entry.getValue().length());
            }
            Files.write(tempPath, lines, StandardCharsets.UTF_8);
            atomicMove(tempPath, indexPath);
            logger.debug("已保存 {} 个文档块索引", index.size());
        } catch (IOException e) {
            throw new StorageException(indexFilePath, "saveIndex", StorageException.ERROR_CODE_IO_ERROR,
                    "Failed to save index file", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 刷盘：索引 + 数据。 */
    public void flush() throws IOException {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            saveIndex();
            channel.force(false);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed || mapping == null) {
            throw new StorageException(dataFilePath, "access", StorageException.ERROR_CODE_IO_ERROR,
                    "Document store is closed");
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }

            RuntimeException failure = null;
            try {
                saveIndex();
                channel.force(true);
            } catch (RuntimeException e) {
                failure = e;
            } catch (IOException e) {
                failure = new StorageException(dataFilePath, "close",
                        StorageException.ERROR_CODE_IO_ERROR, "Failed to flush document store", e);
            }

            try {
                if (mapping != null) {
                    mapping.arena().close();
                    mapping = null;
                }
                channel.close();
            } catch (IOException | RuntimeException e) {
                if (failure == null) {
                    failure = new StorageException(dataFilePath, "close",
                            StorageException.ERROR_CODE_IO_ERROR, "Failed to close document store", e);
                }
            } finally {
                closed = true;
            }

            if (failure != null) {
                throw failure;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    public String getDataFilePath() {
        return dataFilePath;
    }
}
