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

package cn.nexon.zerovector.core.storage.local;

import cn.nexon.zerovector.core.model.SourceRef;
import cn.nexon.zerovector.core.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;

/**
 * 文档原文存储。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li>{@link SourceType#MANAGED_COPY}：把解码后的正文写成 UTF-8 副本，查询/回答
 *       与用户原文件隔离；</li>
 *   <li>{@link SourceType#ORIGINAL}：只保存原文件绝对路径，不复制。</li>
 * </ul>
 *
 * <p>章节读取按字节区间进行，不把整篇大文件读进内存。
 */
public final class DocumentSourceStore {

    private static final Logger logger = LoggerFactory.getLogger(DocumentSourceStore.class);

    private final Path sourcesDir;

    public DocumentSourceStore(Path sourcesDir) throws IOException {
        this.sourcesDir = sourcesDir.toAbsolutePath();
        Files.createDirectories(this.sourcesDir);
    }

    /** 保存 UTF-8 托管副本，返回引用。 */
    public SourceRef saveCopy(String content, String originalName) throws IOException {
        try (CopySession session = beginCopy(originalName)) {
            session.writer().write(content);
            return session.finish();
        }
    }

    /** 开始流式写副本；调用方 write 完后必须 finish()，失败时 abort()。 */
    public CopySession beginCopy(String originalName) throws IOException {
        String safeName = sanitize(originalName == null ? "document" : originalName);
        String id = UUID.randomUUID() + "_" + safeName;
        Path target = sourcesDir.resolve(id);
        Path temp = sourcesDir.resolve(id + ".tmp");
        Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
        return new CopySession(id, target, temp, writer);
    }

    public final class CopySession implements AutoCloseable {
        private final String id;
        private final Path target;
        private final Path temp;
        private final Writer writer;
        private boolean finished;

        private CopySession(String id, Path target, Path temp, Writer writer) {
            this.id = id;
            this.target = target;
            this.temp = temp;
            this.writer = writer;
        }

        public Writer writer() {
            return writer;
        }

        public SourceRef finish() throws IOException {
            writer.close();
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            finished = true;
            return new SourceRef(SourceType.MANAGED_COPY, id);
        }

        public void abort() {
            try {
                writer.close();
            } catch (IOException ignored) {
                // best effort
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
        }

        @Override
        public void close() {
            if (!finished) {
                abort();
            }
        }
    }

    public SourceRef reference(Path originalFile) {
        return new SourceRef(SourceType.ORIGINAL, originalFile.toAbsolutePath().normalize().toString());
    }

    public Path resolve(SourceRef ref) {
        return switch (ref.type()) {
            case MANAGED_COPY -> sourcesDir.resolve(ref.id()).normalize();
            case ORIGINAL -> Paths.get(ref.id()).toAbsolutePath().normalize();
        };
    }

    /**
     * 按字节区间读取原文并解码。
     *
     * @param ref     源引用
     * @param offset  字节偏移
     * @param length  字节长度
     * @param charset 源文件编码（托管副本固定 UTF-8）
     */
    public String read(SourceRef ref, long offset, int length, String charset) throws IOException {
        if (length <= 0) {
            return "";
        }
        if (offset < 0) {
            throw new IOException("Negative source offset: " + offset);
        }

        Path path = resolve(ref);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (offset + length > channel.size()) {
                throw new IOException("Source range out of bounds: " + path
                        + " offset=" + offset + ", length=" + length + ", size=" + channel.size());
            }
            ByteBuffer buffer = ByteBuffer.allocate(length);
            long position = offset;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new IOException("Unexpected EOF reading source: " + path);
                }
                position += read;
            }
            return new String(buffer.array(), Charset.forName(charset));
        }
    }

    public boolean exists(SourceRef ref) {
        return Files.isRegularFile(resolve(ref));
    }

    /** 只删除系统托管副本；原文件引用不删除用户文件。 */
    public void delete(SourceRef ref) {
        if (ref == null || ref.type() != SourceType.MANAGED_COPY) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(ref));
        } catch (IOException e) {
            logger.warn("删除文档副本失败: {}", ref.id(), e);
        }
    }

    /**
     * 清理未提交/已失去引用的托管副本。
     *
     * @param keepIds 仍被语义树引用的副本 id
     */
    public void cleanupManagedCopies(Set<String> keepIds) {
        try (var files = Files.list(sourcesDir)) {
            files.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        boolean temporary = name.endsWith(".tmp");
                        if (temporary || !keepIds.contains(name)) {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                logger.warn("清理孤儿文档副本失败: {}", path, e);
                            }
                        }
                    });
        } catch (IOException e) {
            logger.warn("扫描文档副本目录失败: {}", sourcesDir, e);
        }
    }

    private static String sanitize(String fileName) {
        String name = Paths.get(fileName).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isBlank()) {
            return "document";
        }
        return name.length() > 80 ? name.substring(0, 80) : name;
    }
}
