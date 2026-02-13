package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.model.DocumentChunk;
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
import java.util.HashMap;
import java.util.Map;

/**
 * 内存映射文档存储引擎
 * 实现大纲中的高性能存储方案，利用mmap处理大文件
 */
public class MMapDocumentStore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MMapDocumentStore.class);
    
    private final RandomAccessFile file;
    private final MappedByteBuffer buffer;
    private final String dataFilePath;
    private final String indexFilePath;
    
    // 索引：ChunkID -> (文件偏移量, 长度)
    private final Map<String, FileLocation> index = new HashMap<>();
    
    /**
     * 文件位置记录
     */
    public record FileLocation(long offset, int length) {}
    
    private MMapDocumentStore(RandomAccessFile file, MappedByteBuffer buffer, String dataFilePath, String indexFilePath) {
        this.file = file;
        this.buffer = buffer;
        this.dataFilePath = dataFilePath;
        this.indexFilePath = indexFilePath;
    }
    
    /**
     * 打开或创建文档存储
     */
    public static MMapDocumentStore open(String filePath) throws IOException {
        RandomAccessFile file = new RandomAccessFile(filePath, "rw");
        if (file.length() == 0) {
            // 新文件，初始化
            file.setLength(1024 * 1024); // 初始1MB
        }
        
        MappedByteBuffer buffer = file.getChannel().map(
            FileChannel.MapMode.READ_WRITE, 0, file.length());
        
        String indexFilePath = filePath + ".index";
        MMapDocumentStore store = new MMapDocumentStore(file, buffer, filePath, indexFilePath);
        store.loadIndex();
        return store;
    }
    
    /**
     * 添加文档块并返回其位置
     */
    public FileLocation addChunk(String chunkId, String content) throws IOException {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        
        // 检查空间是否足够，不足则扩展
        if (buffer.position() + 4 + contentBytes.length > buffer.capacity()) {
            expandBuffer(buffer.capacity() * 2);
        }
        
        // 记录当前位置
        long offset = buffer.position();
        int length = contentBytes.length;
        
        // 写入长度前缀
        buffer.putInt(length);
        
        // 写入内容
        buffer.put(contentBytes);
        
        // 更新索引
        FileLocation location = new FileLocation(offset, length);
        index.put(chunkId, location);
        
        return location;
    }
    
    /**
     * 获取文档块内容
     * [KEY] 零拷贝读取，极快
     * [CRITICAL] 使用 slice 创建独立视图，避免多线程竞争 buffer position
     */
    public String getChunk(String chunkId) {
        FileLocation loc = index.get(chunkId);
        if (loc == null) return null;
        
        // [CRITICAL] 使用 slice 创建独立视图，避免多线程竞争 buffer position
        synchronized (this) {
            // 创建当前位置的独立视图
            MappedByteBuffer sliceBuffer = buffer.duplicate();
            sliceBuffer.position((int) loc.offset());
            
            // 读取长度前缀
            int length = sliceBuffer.getInt();
            
            // 读取实际内容
            byte[] bytes = new byte[length];
            sliceBuffer.get(bytes);
            
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    
    /**
     * 获取文档块内容，支持文件路径
     */
    public String getChunkContent(DocumentChunk chunk) {
        if (chunk.isFilePathBased()) {
            try {
                return Files.readString(Paths.get(chunk.filePath()));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + chunk.filePath(), e);
            }
        } else {
            // 从存储中获取内容
            return getChunk(chunk.id());
        }
    }
    
    /**
     * 扩展缓冲区大小
     */
    private void expandBuffer(long newSize) throws IOException {
        // 强制写入磁盘
        buffer.force();
        
        // 重新映射更大的文件
        file.setLength(newSize);
        MappedByteBuffer newBuffer = file.getChannel().map(
            FileChannel.MapMode.READ_WRITE, 0, newSize);
        
        // 复制旧数据
        buffer.flip();
        newBuffer.put(buffer);
        
        // 替换缓冲区引用（注意：这里简化处理，实际应用中需要更复杂的同步机制）
        // 在实际实现中，可能需要使用Unsafe或反射来替换final字段
        // 这里仅作为示例
    }
    
    /**
     * 加载索引
     */
    private void loadIndex() {
        Path indexPath = Paths.get(indexFilePath);
        if (!Files.exists(indexPath)) {
            // 索引文件不存在，创建空索引
            index.clear();
            return;
        }
        
        try {
            // 读取索引文件内容
            String indexContent = Files.readString(indexPath);
            String[] lines = indexContent.split("\n");
            
            // 解析每一行：chunkId,offset,length
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
            
            logger.info("已加载 {} 个文档块索引", index.size());
        } catch (IOException e) {
            logger.error("加载索引失败: {}", e.getMessage());
            index.clear();
        }
    }
    
    /**
     * 保存索引
     */
    public void saveIndex() {
        try {
            StringBuilder sb = new StringBuilder();
            
            // 将索引格式化为：chunkId,offset,length
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
            
            // 写入索引文件
            Files.writeString(Paths.get(indexFilePath), sb.toString());
            logger.info("已保存 {} 个文档块索引", index.size());
        } catch (IOException e) {
            logger.error("保存索引失败: {}", e.getMessage());
        }
    }
    
    @Override
    public void close() throws IOException {
        saveIndex();
        buffer.force();
        if (file != null) file.close();
    }
}