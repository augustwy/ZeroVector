package cn.nexon.zerovector.core.storage.config;

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
}
