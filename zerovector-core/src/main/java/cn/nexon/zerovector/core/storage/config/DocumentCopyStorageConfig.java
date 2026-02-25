package cn.nexon.zerovector.core.storage.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件副本存储配置
 * 
 * <p>用于配置 DocumentCopyStorage 的行为
 */
public class DocumentCopyStorageConfig extends StorageConfig {

    /**
     * 本地存储目录
     * 
     * <p>用于本地文件存储实现，指定文档副本的存储目录
     */
    private String directory;

    /**
     * 扩展配置
     * 
     * <p>用于第三方实现存储自定义配置，如 MinIO 的 endpoint、bucket 等
     */
    private Map<String, Object> extended = new HashMap<>();

    public DocumentCopyStorageConfig() {
        super("local-file");
    }

    public DocumentCopyStorageConfig(String directory) {
        this();
        this.directory = directory;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
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
    public DocumentCopyStorageConfig addExtended(String key, Object value) {
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
