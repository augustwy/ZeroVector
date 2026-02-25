package cn.nexon.zerovector.core.storage.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档分片存储配置
 * 
 * <p>用于配置 ChunkStorage 的行为
 */
public class ChunkStorageConfig extends StorageConfig {

    /**
     * 本地存储基础路径
     * 
     * <p>用于本地文件存储实现，指定数据文件的存储位置
     */
    private String basePath;

    /**
     * 是否使用 mmap
     * 
     * <p>启用后使用内存映射文件进行高效读写
     */
    private boolean useMmap = true;

    /**
     * 是否使用分片存储
     * 
     * <p>启用后将数据分片存储，适合大数据量场景
     */
    private boolean sharded = false;

    /**
     * 分片大小
     * 
     * <p>每个分片文件包含的最大记录数
     */
    private int shardSize = 100;

    /**
     * 扩展配置
     * 
     * <p>用于第三方实现存储自定义配置
     */
    private Map<String, Object> extended = new HashMap<>();

    public ChunkStorageConfig() {
        super("local-mmap");
    }

    public ChunkStorageConfig(String basePath) {
        this();
        this.basePath = basePath;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public boolean isUseMmap() {
        return useMmap;
    }

    public void setUseMmap(boolean useMmap) {
        this.useMmap = useMmap;
    }

    public boolean isSharded() {
        return sharded;
    }

    public void setSharded(boolean sharded) {
        this.sharded = sharded;
    }

    public int getShardSize() {
        return shardSize;
    }

    public void setShardSize(int shardSize) {
        this.shardSize = shardSize;
    }

    public Map<String, Object> getExtended() {
        return extended;
    }

    public void setExtended(Map<String, Object> extended) {
        this.extended = extended != null ? extended : new HashMap<>();
    }

    /**
     * 添加扩展配置
     * 
     * @param key 配置键
     * @param value 配置值
     * @return 当前配置对象
     */
    public ChunkStorageConfig addExtended(String key, Object value) {
        this.extended.put(key, value);
        return this;
    }

    /**
     * 获取扩展配置
     * 
     * @param key 配置键
     * @return 配置值
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtended(String key) {
        return (T) extended.get(key);
    }

    /**
     * 获取扩展配置
     * 
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值，如果不存在则返回默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtended(String key, T defaultValue) {
        Object value = extended.get(key);
        return value != null ? (T) value : defaultValue;
    }
}
