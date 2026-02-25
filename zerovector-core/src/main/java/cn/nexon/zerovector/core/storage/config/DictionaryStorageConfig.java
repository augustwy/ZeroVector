package cn.nexon.zerovector.core.storage.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 字典存储配置
 * 
 * <p>用于配置 DictionaryStorage 的行为
 */
public class DictionaryStorageConfig extends StorageConfig {

    /**
     * 本地存储文件路径
     * 
     * <p>用于本地文件存储实现，指定字典文件的存储位置
     */
    private String filePath;

    /**
     * 扩展配置
     * 
     * <p>用于第三方实现存储自定义配置
     */
    private Map<String, Object> extended = new HashMap<>();

    public DictionaryStorageConfig() {
        super("local-file");
    }

    public DictionaryStorageConfig(String filePath) {
        this();
        this.filePath = filePath;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
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
    public DictionaryStorageConfig addExtended(String key, Object value) {
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
