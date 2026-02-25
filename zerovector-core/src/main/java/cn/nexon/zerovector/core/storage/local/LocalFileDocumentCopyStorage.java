package cn.nexon.zerovector.core.storage.local;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.storage.config.DocumentCopyStorageConfig;
import cn.nexon.zerovector.core.storage.model.DocumentCopyInfo;
import cn.nexon.zerovector.core.storage.spi.DocumentCopyStorage;
import cn.nexon.zerovector.core.storage.spi.StorageProvider;
import cn.nexon.zerovector.core.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地文件副本存储
 * 
 * <p>基于本地文件系统的文档副本存储实现
 * 
 * <p>存储结构：
 * <pre>
 * {directory}/
 * ├── {timestamp}_{filename1}
 * ├── {timestamp}_{filename2}
 * └── ...
 * </pre>
 */
@StorageProvider(type = "local-file", priority = 1, description = "本地文件副本存储")
public class LocalFileDocumentCopyStorage implements DocumentCopyStorage {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileDocumentCopyStorage.class);

    private String documentsDir;
    private DocumentCopyStorageConfig config;
    private boolean initialized = false;

    @Override
    public void initialize(DocumentCopyStorageConfig config) throws StorageException {
        if (initialized) {
            logger.warn("LocalFileDocumentCopyStorage 已经初始化");
            return;
        }

        this.config = config;
        this.documentsDir = config.getDirectory();

        try {
            FileUtils.createDirectories(Paths.get(documentsDir));
            initialized = true;
            logger.debug("LocalFileDocumentCopyStorage 初始化完成, directory: {}", documentsDir);
        } catch (IOException e) {
            throw new StorageException(documentsDir, "initialize", e);
        }
    }

    @Override
    public String saveCopy(Path originalPath) throws StorageException {
        checkInitialized();

        if (originalPath == null || !Files.exists(originalPath)) {
            throw new StorageException(originalPath != null ? originalPath.toString() : "null",
                "saveCopy", new IllegalArgumentException("原始文件不存在"));
        }

        try {
            Path copiedFile = FileUtils.copyFileWithTimestamp(originalPath, Paths.get(documentsDir));
            String fileId = copiedFile.getFileName().toString();
            logger.debug("保存文件副本: {} -> {}", originalPath, fileId);
            return fileId;
        } catch (IOException e) {
            throw new StorageException(originalPath.toString(), "saveCopy", e);
        }
    }

    @Override
    public String saveCopy(Path originalPath, String customId) throws StorageException {
        checkInitialized();

        if (originalPath == null || !Files.exists(originalPath)) {
            throw new StorageException(originalPath != null ? originalPath.toString() : "null",
                "saveCopy", new IllegalArgumentException("原始文件不存在"));
        }

        if (customId == null || customId.isEmpty()) {
            return saveCopy(originalPath);
        }

        try {
            Path targetPath = Paths.get(documentsDir, customId);
            Files.copy(originalPath, targetPath);
            logger.debug("保存文件副本（自定义ID）: {} -> {}", originalPath, customId);
            return customId;
        } catch (IOException e) {
            throw new StorageException(originalPath.toString(), "saveCopy", e);
        }
    }

    @Override
    public String saveCopy(InputStream inputStream, String fileName) throws StorageException {
        checkInitialized();

        if (inputStream == null) {
            throw new StorageException("null", "saveCopy", new IllegalArgumentException("输入流不能为 null"));
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        String fileId = timestamp + "_" + (fileName != null ? sanitizeFileName(fileName) : "unknown");

        try {
            Path targetPath = Paths.get(documentsDir, fileId);
            Files.copy(inputStream, targetPath);
            logger.debug("保存文件副本（输入流）: {}", fileId);
            return fileId;
        } catch (IOException e) {
            throw new StorageException(fileId, "saveCopy", e);
        }
    }

    @Override
    public byte[] getContent(String fileId) throws StorageException {
        checkInitialized();

        if (fileId == null || fileId.isEmpty()) {
            return null;
        }

        Optional<Path> filePath = getFilePath(fileId);
        if (filePath.isEmpty()) {
            return null;
        }

        try {
            return Files.readAllBytes(filePath.get());
        } catch (IOException e) {
            throw new StorageException(fileId, "getContent", e);
        }
    }

    @Override
    public InputStream getInputStream(String fileId) throws StorageException {
        checkInitialized();

        if (fileId == null || fileId.isEmpty()) {
            return null;
        }

        Optional<Path> filePath = getFilePath(fileId);
        if (filePath.isEmpty()) {
            return null;
        }

        try {
            return Files.newInputStream(filePath.get());
        } catch (IOException e) {
            throw new StorageException(fileId, "getInputStream", e);
        }
    }

    @Override
    public Optional<Path> getFilePath(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            return Optional.empty();
        }

        Path path = Paths.get(documentsDir, fileId);
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    @Override
    public void deleteCopy(String fileId) throws StorageException {
        checkInitialized();

        if (fileId == null || fileId.isEmpty()) {
            return;
        }

        Optional<Path> filePath = getFilePath(fileId);
        if (filePath.isPresent()) {
            try {
                Files.delete(filePath.get());
                logger.debug("删除文件副本: {}", fileId);
            } catch (IOException e) {
                throw new StorageException(fileId, "deleteCopy", e);
            }
        }
    }

    @Override
    public boolean exists(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            return false;
        }

        return getFilePath(fileId).isPresent();
    }

    @Override
    public List<String> getAllFileIds() {
        List<String> fileIds = new ArrayList<>();

        try (Stream<Path> stream = Files.list(Paths.get(documentsDir))) {
            stream.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .forEach(fileIds::add);
        } catch (IOException e) {
            logger.warn("获取文件列表失败: {}", e.getMessage());
        }

        return fileIds;
    }

    @Override
    public Optional<DocumentCopyInfo> getFileInfo(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            return Optional.empty();
        }

        Optional<Path> filePath = getFilePath(fileId);
        if (filePath.isEmpty()) {
            return Optional.empty();
        }

        try {
            Path path = filePath.get();
            long size = Files.size(path);
            String contentType = Files.probeContentType(path);
            Instant createdAt = Files.getLastModifiedTime(path).toInstant();

            String fileName = extractOriginalFileName(fileId);

            return Optional.of(new DocumentCopyInfo(
                fileId,
                fileName,
                size,
                contentType,
                createdAt,
                null
            ));
        } catch (IOException e) {
            logger.warn("获取文件信息失败: {}", fileId, e);
            return Optional.empty();
        }
    }

    @Override
    public void clear() throws StorageException {
        checkInitialized();

        List<String> fileIds = getAllFileIds();
        for (String fileId : fileIds) {
            deleteCopy(fileId);
        }

        logger.debug("清空所有文件副本: {} 个", fileIds.size());
    }

    @Override
    public String getStorageType() {
        return "local-file";
    }

    @Override
    public boolean isHealthy() {
        return initialized && documentsDir != null && Files.isDirectory(Paths.get(documentsDir));
    }

    @Override
    public void close() throws IOException {
        initialized = false;
        logger.debug("LocalFileDocumentCopyStorage 已关闭");
    }

    /**
     * 获取存储目录
     * 
     * @return 存储目录路径
     */
    public String getDocumentsDir() {
        return documentsDir;
    }

    private void checkInitialized() throws StorageException {
        if (!initialized) {
            throw new StorageException("storage", "not initialized",
                new IllegalStateException("存储未初始化"));
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String extractOriginalFileName(String fileId) {
        if (fileId == null) {
            return "unknown";
        }

        int underscoreIndex = fileId.indexOf('_');
        if (underscoreIndex > 0 && underscoreIndex < fileId.length() - 1) {
            return fileId.substring(underscoreIndex + 1);
        }

        return fileId;
    }
}
