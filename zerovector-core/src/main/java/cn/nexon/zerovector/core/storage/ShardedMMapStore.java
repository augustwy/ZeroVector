package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 哈希分片 mmap 存储包装器。
 *
 * <p>将 chunkId 按哈希值路由到多个 {@link MMapDocumentStore} 实例，
 * 每个分片有独立的文件、buffer 和锁，消除单缓冲区全局锁争用。
 *
 * <p>文件布局：
 * <pre>
 * {basePath}_shard0.data / .data.index
 * {basePath}_shard1.data / .data.index
 * ...
 * </pre>
 */
public class ShardedMMapStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ShardedMMapStore.class);

    private final MMapDocumentStore[] shards;

    private ShardedMMapStore(MMapDocumentStore[] shards) {
        this.shards = shards;
    }

    /**
     * 打开分片存储。
     *
     * @param basePath   数据文件基础路径（不含后缀）
     * @param shardCount 分片数
     * @throws IOException 文件打开失败
     */
    public static ShardedMMapStore open(String basePath, int shardCount) throws IOException {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got: " + shardCount);
        }
        MMapDocumentStore[] shards = new MMapDocumentStore[shardCount];
        for (int i = 0; i < shardCount; i++) {
            String shardPath = basePath + "_shard" + i + ".data";
            shards[i] = MMapDocumentStore.open(shardPath);
        }
        logger.debug("分片 mmap 存储已打开: basePath={}, shardCount={}", basePath, shardCount);
        return new ShardedMMapStore(shards);
    }

    private int shardFor(String chunkId) {
        return Math.abs(chunkId.hashCode()) % shards.length;
    }

    public MMapDocumentStore.FileLocation addChunk(String chunkId, String content) throws IOException {
        return shards[shardFor(chunkId)].addChunk(chunkId, content);
    }

    public String getChunk(String chunkId) {
        return shards[shardFor(chunkId)].getChunk(chunkId);
    }

    public String getChunkContent(DocumentChunk chunk) {
        return shards[shardFor(chunk.id())].getChunkContent(chunk);
    }

    public boolean deleteChunk(String chunkId) {
        return shards[shardFor(chunkId)].deleteChunk(chunkId);
    }

    public void saveIndex() {
        for (MMapDocumentStore shard : shards) {
            shard.saveIndex();
        }
    }

    public void compact() throws IOException {
        for (MMapDocumentStore shard : shards) {
            shard.compact();
        }
    }

    public String getUsageInfo() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shards.length; i++) {
            sb.append("shard").append(i).append(": ").append(shards[i].getUsageInfo()).append("\n");
        }
        return sb.toString();
    }

    public int getShardCount() {
        return shards.length;
    }

    @Override
    public void close() {
        for (MMapDocumentStore shard : shards) {
            try {
                shard.close();
            } catch (IOException e) {
                logger.error("关闭分片失败", e);
            }
        }
    }
}
