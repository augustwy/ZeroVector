package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.exception.CacheException;
import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.util.MmapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于 MMap 的文档存储
 * 使用内存映射文件实现高效的文档块存储
 */
public class MMapDocumentStore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MMapDocumentStore.class);
    private static final int INITIAL_BUFFER_SIZE = 1024 * 1024;
    
    private RandomAccessFile file;
    private final AtomicReference<MappedByteBuffer> bufferRef;
    private final String dataFilePath;
    private final String indexFilePath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    private MappedByteBuffer buffer() {
        return bufferRef.get();
    }
    
    private void setBuffer(MappedByteBuffer newBuffer) {
        bufferRef.set(newBuffer);
    }
    
    private final Map<String, FileLocation> index = new ConcurrentHashMap<>();
    
    public record FileLocation(long offset, int length) {}
    
    private MMapDocumentStore(RandomAccessFile file, MappedByteBuffer buffer, String dataFilePath, String indexFilePath) {
        this.file = file;
        this.bufferRef = new AtomicReference<>(buffer);
        this.dataFilePath = dataFilePath;
        this.indexFilePath = indexFilePath;
    }
    
    public static MMapDocumentStore open(String filePath) throws IOException {
        try {
            RandomAccessFile file = new RandomAccessFile(filePath, "rw");
            if (file.length() == 0) {
                file.setLength(INITIAL_BUFFER_SIZE);
            }
            
            MappedByteBuffer buffer = file.getChannel().map(
                FileChannel.MapMode.READ_WRITE, 0, file.length());
            
            String indexFilePath = filePath + ".index";
            MMapDocumentStore store = new MMapDocumentStore(file, buffer, filePath, indexFilePath);
            store.loadIndex();
            return store;
        } catch (IOException e) {
            throw new StorageException(filePath, "open", StorageException.ERROR_CODE_IO_ERROR, "Failed to open document store", e);
        }
    }
    
    /**
     * 添加文档块
     *
     * @param chunkId 文档块 ID
     * @param content 文档块内容
     * @return 文件位置信息
     */
    public FileLocation addChunk(String chunkId, String content) throws IOException {
        lock.writeLock().lock();
        try {
            MappedByteBuffer currentBuffer = buffer();
            if (currentBuffer == null) {
                throw new StorageException(dataFilePath, "addChunk", StorageException.ERROR_CODE_IO_ERROR,
                    "Document store is closed");
            }
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            
            if (currentBuffer.position() + 4 + contentBytes.length > currentBuffer.capacity()) {
                expandBuffer(currentBuffer.capacity() * 2);
                logger.debug("缓冲区扩展, 新大小: {}MB", (currentBuffer.capacity() * 2) / (1024 * 1024));
                currentBuffer = buffer();
            }
            
            long offset = currentBuffer.position();
            int length = contentBytes.length;
            
            currentBuffer.putInt(length);
            currentBuffer.put(contentBytes);
            
            FileLocation location = new FileLocation(offset, length);
            index.put(chunkId, location);
            
            logger.debug("文档块写入完成, chunkId: {}, 大小: {} bytes", chunkId, length);
            
            return location;
        } catch (IOException e) {
            throw new StorageException(dataFilePath, "addChunk", StorageException.ERROR_CODE_IO_ERROR, "Failed to add chunk to store", e);
        } catch (Exception e) {
            throw new CacheException(chunkId, "addChunk", CacheException.ERROR_CODE_PUT_FAILED, 
                "Failed to add chunk to store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取文档块内容
     *
     * @param chunkId 文档块 ID
     * @return 文档块内容
     */
    public String getChunk(String chunkId) {
        lock.readLock().lock();
        try {
            MappedByteBuffer currentBuffer = buffer();
            if (currentBuffer == null) {
                logger.warn("Document store is closed, cannot get chunk: {}", chunkId);
                return null;
            }
            FileLocation loc = index.get(chunkId);
            if (loc == null) {
                logger.debug("文档块不存在, chunkId: {}", chunkId);
                return null;
            }

            MappedByteBuffer sliceBuffer = currentBuffer.duplicate();
            sliceBuffer.position((int) loc.offset());
            
            int length = sliceBuffer.getInt();
            
            byte[] bytes = new byte[length];
            sliceBuffer.get(bytes);
            
            logger.debug("文档块读取完成, chunkId: {}, 大小: {} bytes", chunkId, length);
            
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("获取文档块失败, chunkId: {}", chunkId, e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取文档块内容
     * 根据文档块类型选择读取方式
     *
     * @param chunk 文档块
     * @return 文档块内容
     */
    public String getChunkContent(DocumentChunk chunk) {
        if (chunk.isFilePathBased()) {
            try {
                return Files.readString(Paths.get(chunk.filePath()));
            } catch (IOException e) {
                throw new StorageException(chunk.filePath(), "read file", e);
            }
        } else {
            return getChunk(chunk.id());
        }
    }
    
    /**
     * 删除文档块
     *
     * @param chunkId 文档块 ID
     * @return 是否删除成功
     */
    public boolean deleteChunk(String chunkId) {
        lock.writeLock().lock();
        try {
            if (index.containsKey(chunkId)) {
                index.remove(chunkId);
                logger.debug("文档块删除完成, chunkId: {}", chunkId);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 清理无效数据，回收空间
     * 注意：此操作会重建存储文件，可能耗时较长
     */
    public void compact() throws IOException {
        lock.writeLock().lock();
        try {
            if (index.isEmpty()) {
                // 清空文件
                file.setLength(INITIAL_BUFFER_SIZE);
                MappedByteBuffer newBuffer = file.getChannel().map(
                    FileChannel.MapMode.READ_WRITE, 0, INITIAL_BUFFER_SIZE);
                setBuffer(newBuffer);
                logger.debug("存储已清空");
                return;
            }
            
            // 创建临时文件
            String tempFilePath = dataFilePath + ".tmp";
            try (RandomAccessFile tempFile = new RandomAccessFile(tempFilePath, "rw")) {
                tempFile.setLength(INITIAL_BUFFER_SIZE);
                MappedByteBuffer tempBuffer = tempFile.getChannel().map(
                    FileChannel.MapMode.READ_WRITE, 0, INITIAL_BUFFER_SIZE);
                
                // 重建索引和数据
                Map<String, FileLocation> newIndex = new ConcurrentHashMap<>();
                
                for (Map.Entry<String, FileLocation> entry : index.entrySet()) {
                    String chunkId = entry.getKey();
                    FileLocation oldLocation = entry.getValue();
                    
                    // 读取旧数据
                    MappedByteBuffer oldBuffer = buffer().duplicate();
                    oldBuffer.position((int) oldLocation.offset());
                    int length = oldBuffer.getInt();
                    byte[] bytes = new byte[length];
                    oldBuffer.get(bytes);
                    
                    // 写入新位置
                    if (tempBuffer.position() + 4 + bytes.length > tempBuffer.capacity()) {
                        // 扩展临时缓冲区
                        long newSize = tempBuffer.capacity() * 2;
                        tempFile.setLength(newSize);
                        MappedByteBuffer newTempBuffer = tempFile.getChannel().map(
                            FileChannel.MapMode.READ_WRITE, 0, newSize);
                        tempBuffer.rewind();
                        newTempBuffer.put(tempBuffer);
                        newTempBuffer.position(tempBuffer.position());
                        tempBuffer = newTempBuffer;
                    }
                    
                    long newOffset = tempBuffer.position();
                    tempBuffer.putInt(length);
                    tempBuffer.put(bytes);
                    
                    newIndex.put(chunkId, new FileLocation(newOffset, length));
                }
                
                // 替换文件 - 安全操作：先备份原文件，再替换
                String backupFilePath = dataFilePath + ".old";
                file.close();
                
                // 1. 将原文件重命名为备份
                if (Files.exists(Paths.get(dataFilePath))) {
                    Files.move(Paths.get(dataFilePath), Paths.get(backupFilePath));
                }
                
                try {
                    // 2. 将临时文件重命名为新文件
                    Files.move(Paths.get(tempFilePath), Paths.get(dataFilePath));
                    
                    // 3. 重新打开文件
                    file = new RandomAccessFile(dataFilePath, "rw");
                    MappedByteBuffer newBuffer = file.getChannel().map(
                        FileChannel.MapMode.READ_WRITE, 0, file.length());
                    setBuffer(newBuffer);
                    index.clear();
                    index.putAll(newIndex);
                    
                    // 4. 删除备份文件
                    Files.deleteIfExists(Paths.get(backupFilePath));
                    
                    logger.debug("存储压缩完成，清理了无效数据");
                } catch (Exception e) {
                    // 如果失败，尝试从备份恢复
                    logger.error("文件替换失败，尝试从备份恢复", e);
                    try {
                        if (Files.exists(Paths.get(backupFilePath))) {
                            Files.move(Paths.get(backupFilePath), Paths.get(dataFilePath));
                        }
                        // 重新打开原始文件
                        file = new RandomAccessFile(dataFilePath, "rw");
                        MappedByteBuffer newBuffer = file.getChannel().map(
                            FileChannel.MapMode.READ_WRITE, 0, file.length());
                        setBuffer(newBuffer);
                    } catch (IOException ex) {
                        logger.error("从备份恢复失败: {}", ex.getMessage(), ex);
                    } finally {
                        // 清理临时文件
                        Files.deleteIfExists(Paths.get(tempFilePath));
                    }
                    throw e;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取存储使用情况
     *
     * @return 存储使用情况，格式：已使用字节数/总容量字节数
     */
    public String getUsageInfo() {
        lock.readLock().lock();
        try {
            long used = buffer().position();
            long total = buffer().capacity();
            return String.format("%d/%d bytes (%.2f%%)", used, total, (double) used / total * 100);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    private void expandBuffer(long newSize) throws IOException {
        try {
            MappedByteBuffer oldBuffer = buffer();
            oldBuffer.force();
            
            long oldSize = file.length();
            int oldPosition = oldBuffer.position();
            
            file.setLength(newSize);
            MappedByteBuffer newBuffer = file.getChannel().map(
                FileChannel.MapMode.READ_WRITE, 0, newSize);
            
            oldBuffer.rewind();
            newBuffer.put(oldBuffer);
            
            newBuffer.position(oldPosition);

            setBuffer(newBuffer);

            // 主动释放旧的映射缓冲区，避免依赖GC回收
            MmapUtils.clean(oldBuffer);
        } catch (IOException e) {
            throw new StorageException(dataFilePath, "expandBuffer", StorageException.ERROR_CODE_BUFFER_OVERFLOW, "Failed to expand buffer", e);
        }
    }
    
    private void loadIndex() {
        Path indexPath = Paths.get(indexFilePath);
        if (!Files.exists(indexPath)) {
            index.clear();
            return;
        }
        
        try {
            String indexContent = Files.readString(indexPath);
            String[] lines = indexContent.split("\n");
            
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    String chunkId = parts[0];
                    long offset = Long.parseLong(parts[1]);
                    int length = Integer.parseInt(parts[2]);
                    
                    index.put(chunkId, new FileLocation(offset, length));
                }
            }
            
            logger.debug("已加载 {} 个文档块索引", index.size());
        } catch (IOException e) {
            throw new StorageException(indexFilePath, "loadIndex", StorageException.ERROR_CODE_IO_ERROR, "Failed to read index file", e);
        } catch (Exception e) {
            throw new StorageException(indexFilePath, "loadIndex", StorageException.ERROR_CODE_INDEX_CORRUPT, "Failed to parse index file", e);
        }
    }
    
    public void saveIndex() {
        try {
            StringBuilder sb = new StringBuilder();
            
            for (Map.Entry<String, FileLocation> entry : index.entrySet()) {
                String chunkId = entry.getKey();
                FileLocation location = entry.getValue();
                
                sb.append(chunkId)
                  .append(",")
                  .append(location.offset())
                  .append(",")
                  .append(location.length())
                  .append("\n");
            }
            
            Files.writeString(Paths.get(indexFilePath), sb.toString());
            logger.debug("已保存 {} 个文档块索引", index.size());
        } catch (IOException e) {
            throw new StorageException(indexFilePath, "saveIndex", StorageException.ERROR_CODE_IO_ERROR, "Failed to save index file", e);
        }
    }
    
    @Override
    public void close() throws IOException {
        try {
            saveIndex();
            MappedByteBuffer buffer = buffer();
            if (buffer != null) {
                buffer.force();
                MmapUtils.clean(buffer);
            }
            if (file != null) {
                file.close();
                file = null;
            }
            // 在文件和 buffer 清理完毕后再置空引用，防止并发访问半关闭状态
            setBuffer(null);
        } catch (IOException e) {
            throw new StorageException(dataFilePath, "close", StorageException.ERROR_CODE_IO_ERROR, "Failed to close document store", e);
        }
    }
    
    /**
     * 获取数据文件路径
     * @return 数据文件路径
     */
    public String getDataFilePath() {
        return dataFilePath;
    }
}
