package cn.nexon.zerovector.core.storage.spi;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.storage.config.ChunkStorageConfig;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 文档分片存储接口
 * 
 * <p>第三方可以通过实现此接口来扩展存储介质，例如：
 * <ul>
 *   <li>Elasticsearch - 支持全文检索</li>
 *   <li>数据库 - 支持事务</li>
 *   <li>分布式存储 - 支持集群部署</li>
 * </ul>
 * 
 * <p>注意：语义树的路由导航依赖于高性能的随机读取，
 * 建议生产环境使用基于 mmap 的本地文件实现。
 */
public interface ChunkStorage extends AutoCloseable {

    /**
     * 初始化存储
     * 
     * @param config 存储配置
     * @throws StorageException 初始化失败
     */
    void initialize(ChunkStorageConfig config) throws StorageException;

    /**
     * 保存文档分片
     * 
     * @param chunk 文档分片对象
     * @throws StorageException 存储异常
     */
    void saveChunk(DocumentChunk chunk) throws StorageException;

    /**
     * 批量保存文档分片
     * 
     * @param chunks 文档分片集合
     * @throws StorageException 存储异常
     */
    void saveChunks(Map<String, DocumentChunk> chunks) throws StorageException;

    /**
     * 获取文档分片元数据
     * 
     * @param chunkId 分片ID
     * @return 文档分片，不存在返回null
     * @throws StorageException 存储异常
     */
    DocumentChunk getChunk(String chunkId) throws StorageException;

    /**
     * 获取文档分片内容
     * 
     * <p>对于基于文件路径的分片，需要从文件副本存储读取内容
     * 
     * @param chunkId 分片ID
     * @return 分片内容
     * @throws StorageException 存储异常
     */
    String getChunkContent(String chunkId) throws StorageException;

    /**
     * 删除文档分片
     * 
     * @param chunkId 分片ID
     * @throws StorageException 存储异常
     */
    void deleteChunk(String chunkId) throws StorageException;

    /**
     * 检查分片是否存在
     * 
     * @param chunkId 分片ID
     * @return 是否存在
     */
    boolean exists(String chunkId);

    /**
     * 获取所有分片ID
     * 
     * @return 分片ID集合
     */
    Set<String> getAllChunkIds();

    /**
     * 批量获取分片
     * 
     * @param chunkIds 分片ID列表
     * @return 分片映射
     */
    Map<String, DocumentChunk> getChunks(Collection<String> chunkIds);

    /**
     * 清空所有分片
     * 
     * @throws StorageException 存储异常
     */
    void clear() throws StorageException;

    /**
     * 获取存储类型标识
     * 
     * @return 存储类型，如 "local-mmap", "elasticsearch", "mysql" 等
     */
    String getStorageType();

    /**
     * 健康检查
     * 
     * @return 是否健康
     */
    boolean isHealthy();
}
