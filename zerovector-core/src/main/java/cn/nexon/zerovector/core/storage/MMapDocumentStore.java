package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.exception.CacheException;
import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.util.PerformanceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MMapDocumentStore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MMapDocumentStore.class);
    private static final int INITIAL_BUFFER_SIZE = 1024 * 1024;
    
    private final RandomAccessFile file;
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
            throw new StorageException(filePath, "open", e);
        }
    }
    
    public FileLocation addChunk(String chunkId, String content) throws IOException {
        PerformanceMonitor writeMonitor = new PerformanceMonitor("MMapDocumentStore.addChunk");
        writeMonitor.start();
        
        lock.writeLock().lock();
        try {
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            MappedByteBuffer currentBuffer = buffer();
            
            if (currentBuffer.position() + 4 + contentBytes.length > currentBuffer.capacity()) {
                PerformanceMonitor expandMonitor = new PerformanceMonitor("MMapDocumentStore.expandBuffer");
                expandMonitor.start();
                expandBuffer(currentBuffer.capacity() * 2);
                expandMonitor.stop();
                logger.debug("缓冲区扩展耗时: {}ms, 新大小: {}MB", 
                        expandMonitor.getDurationMillis(), (currentBuffer.capacity() * 2) / (1024 * 1024));
                currentBuffer = buffer();
            }
            
            long offset = currentBuffer.position();
            int length = contentBytes.length;
            
            currentBuffer.putInt(length);
            currentBuffer.put(contentBytes);
            
            FileLocation location = new FileLocation(offset, length);
            index.put(chunkId, location);
            
            writeMonitor.stop();
            logger.debug("文档块写入耗时: {}ms, chunkId: {}, 大小: {} bytes", 
                    writeMonitor.getDurationMillis(), chunkId, length);
            
            return location;
        } catch (Exception e) {
            throw new CacheException(chunkId, "addChunk", CacheException.ERROR_CODE_PUT_FAILED, 
                "Failed to add chunk to store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public String getChunk(String chunkId) {
        PerformanceMonitor readMonitor = new PerformanceMonitor("MMapDocumentStore.getChunk");
        readMonitor.start();
        
        lock.readLock().lock();
        try {
            FileLocation loc = index.get(chunkId);
            if (loc == null) {
                throw new CacheException(chunkId, "getChunk", CacheException.ERROR_CODE_KEY_NOT_FOUND, 
                    "Chunk not found in store");
            }
            
            MappedByteBuffer sliceBuffer = buffer().duplicate();
            sliceBuffer.position((int) loc.offset());
            
            int length = sliceBuffer.getInt();
            
            byte[] bytes = new byte[length];
            sliceBuffer.get(bytes);
            
            readMonitor.stop();
            logger.debug("文档块读取耗时: {}ms, chunkId: {}, 大小: {} bytes", 
                    readMonitor.getDurationMillis(), chunkId, length);
            
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CacheException(chunkId, "getChunk", CacheException.ERROR_CODE_GET_FAILED, 
                "Failed to get chunk from store", e);
        } finally {
            lock.readLock().unlock();
        }
    }
    
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
            
            try {
                Field cleanerField = oldBuffer.getClass().getDeclaredField("cleaner");
                cleanerField.setAccessible(true);
                Object cleaner = cleanerField.get(oldBuffer);
                if (cleaner != null) {
                    cleaner.getClass().getMethod("clean").invoke(cleaner);
                }
            } catch (Exception e) {
                logger.warn("Failed to clean old buffer: {}", e.getMessage());
            }
        } catch (IOException e) {
            throw new StorageException(dataFilePath, "expandBuffer", e);
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
            
            logger.info("已加载 {} 个文档块索引", index.size());
        } catch (IOException e) {
            throw new StorageException(indexFilePath, "loadIndex", e);
        } catch (Exception e) {
            throw new CacheException("unknown", "loadIndex", CacheException.ERROR_CODE_DESERIALIZATION_FAILED, 
                "Failed to parse index file", e);
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
            throw new StorageException(indexFilePath, "saveIndex", e);
        }
    }
    
    @Override
    public void close() throws IOException {
        try {
            saveIndex();
            buffer().force();
            if (file != null) file.close();
        } catch (IOException e) {
            throw new StorageException(dataFilePath, "close", e);
        }
    }
}
