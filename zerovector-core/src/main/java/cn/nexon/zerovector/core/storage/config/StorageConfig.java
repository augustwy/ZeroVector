package cn.nexon.zerovector.core.storage.config;

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
}
