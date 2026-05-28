package cn.nexon.zerovector.core.storage.local;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.storage.ShardedMMapStore;
import cn.nexon.zerovector.core.storage.ShardedTreeStorage;
import cn.nexon.zerovector.core.storage.config.ChunkStorageConfig;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;
import cn.nexon.zerovector.core.storage.spi.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本地文件文档分片存储
 * 
 * <p>基于 mmap 和分片存储的本地文件实现，提供高性能的文档分片存储
 * 
 * <p>存储结构：
 * <pre>
 * {basePath}/
 * ├── chunks.data        # mmap 数据文件
 * ├── chunks.data.index  # mmap 索引文件
 * └── shards/            # 分片存储目录（可选）
 *     ├── metadata.json
 *     ├── chunk_*.json
 *     └── node_*.json
 * </pre>
 */
@StorageProvider(type = "local-mmap", priority = 1, description = "本地文件存储（基于 mmap）")
public class LocalFileChunkStorage implements ChunkStorage {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileChunkStorage.class);

    private ShardedMMapStore mmapStore;
    private ShardedTreeStorage shardedStorage;
    private ChunkStorageConfig config;
    private boolean initialized = false;

    private final Map<String, DocumentChunk> chunkCache = new HashMap<>();

    @Override
    public void initialize(ChunkStorageConfig config) throws StorageException {
        if (initialized) {
            logger.warn("LocalFileChunkStorage 已经初始化");
            return;
        }

        this.config = config;

        try {
            Path basePath = Paths.get(config.getBasePath());
            Files.createDirectories(basePath.getParent());

            if (config.isUseMmap()) {
                int shardCount = Math.max(1, config.getMmapShardCount());
                this.mmapStore = ShardedMMapStore.open(config.getBasePath(), shardCount);
                logger.debug("已初始化分片 mmap 存储: basePath={}, shards={}", config.getBasePath(), shardCount);
            }

            if (config.isSharded()) {
                String shardDir = config.getBasePath() + "_shards";
                this.shardedStorage = new ShardedTreeStorage(shardDir, config.getShardSize());
                logger.debug("已初始化分片存储: {}", shardDir);
            }

            initialized = true;
            logger.debug("LocalFileChunkStorage 初始化完成, basePath: {}", config.getBasePath());
        } catch (IOException e) {
            throw new StorageException(config.getBasePath(), "initialize", e);
        }
    }

    @Override
    public void saveChunk(DocumentChunk chunk) throws StorageException {
        checkInitialized();

        if (chunk == null) {
            throw new IllegalArgumentException("chunk 不能为 null");
        }

        try {
            if (config.isUseMmap() && !chunk.isFilePathBased()) {
                mmapStore.addChunk(chunk.id(), chunk.content());
            }

            chunkCache.put(chunk.id(), chunk);
            logger.debug("保存文档分片: {}", chunk.id());
        } catch (IOException e) {
            throw new StorageException(chunk.id(), "saveChunk", e);
        }
    }

    @Override
    public void saveChunks(Map<String, DocumentChunk> chunks) throws StorageException {
        checkInitialized();

        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        for (DocumentChunk chunk : chunks.values()) {
            saveChunk(chunk);
        }

        logger.debug("批量保存文档分片: {} 个", chunks.size());
    }

    @Override
    public DocumentChunk getChunk(String chunkId) throws StorageException {
        checkInitialized();

        if (chunkId == null || chunkId.isEmpty()) {
            return null;
        }

        DocumentChunk cached = chunkCache.get(chunkId);
        if (cached != null) {
            return cached;
        }

        if (shardedStorage != null) {
            try {
                DocumentChunk chunk = shardedStorage.getChunk(chunkId);
                if (chunk != null) {
                    chunkCache.put(chunkId, chunk);
                    return chunk;
                }
            } catch (IOException e) {
                throw new StorageException(chunkId, "getChunk", e);
            }
        }

        return null;
    }

    @Override
    public String getChunkContent(String chunkId) throws StorageException {
        checkInitialized();

        if (chunkId == null || chunkId.isEmpty()) {
            return null;
        }

        DocumentChunk chunk = getChunk(chunkId);
        if (chunk == null) {
            return null;
        }

        if (chunk.isFilePathBased()) {
            try {
                return Files.readString(Paths.get(chunk.filePath()));
            } catch (IOException e) {
                throw new StorageException(chunk.filePath(), "readChunkContent", e);
            }
        }

        if (config.isUseMmap() && mmapStore != null) {
            return mmapStore.getChunk(chunkId);
        }

        return chunk.content();
    }

    @Override
    public void deleteChunk(String chunkId) throws StorageException {
        checkInitialized();

        if (chunkId == null || chunkId.isEmpty()) {
            return;
        }

        chunkCache.remove(chunkId);
        logger.debug("删除文档分片: {}", chunkId);
    }

    @Override
    public boolean exists(String chunkId) {
        if (chunkId == null || chunkId.isEmpty()) {
            return false;
        }

        if (chunkCache.containsKey(chunkId)) {
            return true;
        }

        if (shardedStorage != null && shardedStorage.getChunkShardIndex().containsKey(chunkId)) {
            return true;
        }

        return false;
    }

    @Override
    public Set<String> getAllChunkIds() {
        Set<String> ids = new HashSet<>(chunkCache.keySet());
        if (shardedStorage != null) {
            ids.addAll(shardedStorage.getChunkShardIndex().keySet());
        }
        return Collections.unmodifiableSet(ids);
    }

    @Override
    public Map<String, DocumentChunk> getChunks(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, DocumentChunk> result = new HashMap<>();
        for (String chunkId : chunkIds) {
            DocumentChunk chunk = getChunk(chunkId);
            if (chunk != null) {
                result.put(chunkId, chunk);
            }
        }

        return result;
    }

    @Override
    public void clear() throws StorageException {
        checkInitialized();

        chunkCache.clear();
        logger.debug("清空所有文档分片");
    }

    @Override
    public String getStorageType() {
        return "local-mmap";
    }

    @Override
    public boolean isHealthy() {
        return initialized && (mmapStore != null || !config.isUseMmap());
    }

    @Override
    public void close() throws IOException {
        if (mmapStore != null) {
            mmapStore.close();
            mmapStore = null;
        }

        if (shardedStorage != null) {
            shardedStorage.close();
            shardedStorage = null;
        }

        chunkCache.clear();
        initialized = false;
        logger.debug("LocalFileChunkStorage 已关闭");
    }

    /**
     * 获取 mmap 存储（供内部使用）
     * 
     * @return mmap 存储实例
     */
    public ShardedMMapStore getMMapStore() {
        return mmapStore;
    }

    /**
     * 获取分片存储（供内部使用）
     * 
     * @return 分片存储实例
     */
    public ShardedTreeStorage getShardedStorage() {
        return shardedStorage;
    }

    /**
     * 保存索引
     * 
     * @throws StorageException 存储异常
     */
    public void saveIndex() throws StorageException {
        if (mmapStore != null) {
            mmapStore.saveIndex();
            logger.debug("已保存 mmap 索引");
        }
    }

    private void checkInitialized() throws StorageException {
        if (!initialized) {
            throw new StorageException("storage", "not initialized",
                new IllegalStateException("存储未初始化"));
        }
    }
}
