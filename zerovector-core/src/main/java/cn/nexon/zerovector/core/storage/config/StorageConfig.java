package cn.nexon.zerovector.core.storage.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 存储配置基类
 *
 * <p>所有存储配置类的基类，包含通用配置属性
 */
public abstract class StorageConfig {

    /**
     * 存储类型标识
     *
     * <p>用于指定使用哪个存储实现，如 "local-mmap", "elasticsearch" 等
     */
    protected String type;

    /**
     * 是否启用
     *
     * <p>设置为 false 时，该存储将被禁用
     */
    protected boolean enabled = true;

    /**
     * 扩展配置
     *
     * <p>用于第三方实现存储自定义配置
     */
    protected Map<String, Object> extended = new HashMap<>();

    protected StorageConfig() {
    }

    protected StorageConfig(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Object> getExtended() {
        return extended;
    }

    public void setExtended(Map<String, Object> extended) {
        this.extended = extended != null ? extended : new HashMap<>();
    }

    public StorageConfig addExtended(String key, Object value) {
        this.extended.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getExtended(String key) {
        return (T) extended.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getExtended(String key, T defaultValue) {
        Object value = extended.get(key);
        return value != null ? (T) value : defaultValue;
    }
}
