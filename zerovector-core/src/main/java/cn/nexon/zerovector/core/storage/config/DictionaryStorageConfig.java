package cn.nexon.zerovector.core.storage.config;

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
}
