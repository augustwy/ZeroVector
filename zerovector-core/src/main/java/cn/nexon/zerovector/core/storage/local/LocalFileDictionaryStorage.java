package cn.nexon.zerovector.core.storage.local;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;
import cn.nexon.zerovector.core.storage.spi.DictionaryStorage;
import cn.nexon.zerovector.core.storage.spi.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 本地文件字典存储
 * 
 * <p>基于 JSON 文件的本地字典存储实现
 * 
 * <p>存储结构：
 * <pre>
 * {filePath}.dict  # JSON 格式的字典文件
 * </pre>
 */
@StorageProvider(type = "local-file", priority = 1, description = "本地文件字典存储")
public class LocalFileDictionaryStorage implements DictionaryStorage {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileDictionaryStorage.class);

    private KeywordDictionary dictionary;
    private DictionaryStorageConfig config;
    private boolean initialized = false;

    @Override
    public void initialize(DictionaryStorageConfig config) throws StorageException {
        if (initialized) {
            logger.warn("LocalFileDictionaryStorage 已经初始化");
            return;
        }

        this.config = config;

        try {
            if (config.getFilePath() != null && !config.getFilePath().isEmpty()) {
                this.dictionary = KeywordDictionary.loadFromFile(config.getFilePath());
                logger.debug("从文件加载字典: {}", config.getFilePath());
            } else {
                this.dictionary = new KeywordDictionary();
                logger.debug("创建新字典");
            }

            initialized = true;
            logger.debug("LocalFileDictionaryStorage 初始化完成, 关键词数量: {}", dictionary.size());
        } catch (IOException e) {
            logger.warn("加载字典失败，创建新字典: {}", e.getMessage());
            this.dictionary = new KeywordDictionary();
            initialized = true;
        }
    }

    @Override
    public void saveKeywordDefinition(KeywordDefinition definition) throws StorageException {
        checkInitialized();

        if (definition == null) {
            throw new IllegalArgumentException("definition 不能为 null");
        }

        dictionary.addKeywordDefinition(definition);
        logger.debug("保存关键词定义: {}", definition.keyword());
    }

    @Override
    public void saveKeywordDefinitions(List<KeywordDefinition> definitions) throws StorageException {
        checkInitialized();

        if (definitions == null || definitions.isEmpty()) {
            return;
        }

        dictionary.addKeywordDefinitions(definitions);
        logger.debug("批量保存关键词定义: {} 个", definitions.size());
    }

    @Override
    public Optional<KeywordDefinition> getKeywordDefinition(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return Optional.empty();
        }

        return dictionary.getKeywordDefinition(keyword);
    }

    @Override
    public void deleteKeywordDefinition(String keyword) throws StorageException {
        checkInitialized();

        if (keyword == null || keyword.isEmpty()) {
            return;
        }

        dictionary.removeKeywordDefinition(keyword);
        logger.debug("删除关键词定义: {}", keyword);
    }

    @Override
    public void addInvertedIndexEntry(String keyword, String nodeId, double weight) throws StorageException {
        checkInitialized();

        if (keyword == null || keyword.isEmpty() || nodeId == null || nodeId.isEmpty()) {
            return;
        }

        dictionary.addEntry(keyword, nodeId, weight);
        logger.debug("添加倒排索引条目: {} -> {}", keyword, nodeId);
    }

    @Override
    public void addInvertedIndexEntries(String keyword, List<String> nodeIds) throws StorageException {
        checkInitialized();

        if (keyword == null || keyword.isEmpty() || nodeIds == null || nodeIds.isEmpty()) {
            return;
        }

        for (String nodeId : nodeIds) {
            dictionary.addEntry(keyword, nodeId, 1.0);
        }
        logger.debug("批量添加倒排索引条目: {} -> {} 个节点", keyword, nodeIds.size());
    }

    @Override
    public List<String> getNodesForKeyword(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return Collections.emptyList();
        }

        return dictionary.getNodesForKeyword(keyword);
    }

    @Override
    public double getKeywordWeight(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return 0.0;
        }

        return dictionary.getKeywordDefinition(keyword)
            .map(k -> 1.0)
            .orElse(0.0);
    }

    @Override
    public Map<String, Double> matchCandidates(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }

        return dictionary.matchCandidates(query);
    }

    @Override
    public Map<String, Double> matchCandidatesFromKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyMap();
        }

        return dictionary.matchCandidatesFromKeywords(keywords);
    }

    @Override
    public Set<String> getAllKeywords() {
        return dictionary.getAllKeywords();
    }

    @Override
    public Collection<KeywordDefinition> getAllKeywordDefinitions() {
        return dictionary.getAllKeywordDefinitions();
    }

    @Override
    public boolean containsKeyword(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return false;
        }

        return dictionary.containsKeyword(keyword);
    }

    @Override
    public void clear() throws StorageException {
        checkInitialized();

        dictionary.clear();
        logger.debug("清空字典");
    }

    @Override
    public int size() {
        return dictionary.size();
    }

    @Override
    public String getStorageType() {
        return "local-file";
    }

    @Override
    public boolean isHealthy() {
        return initialized && dictionary != null;
    }

    @Override
    public void persist() throws StorageException {
        checkInitialized();

        if (config.getFilePath() != null && !config.getFilePath().isEmpty()) {
            try {
                dictionary.saveToFile(config.getFilePath());
                logger.debug("持久化字典到文件: {}", config.getFilePath());
            } catch (IOException e) {
                throw new StorageException(config.getFilePath(), "persist", e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            persist();
        } catch (StorageException e) {
            throw new IOException("持久化字典失败", e);
        }

        initialized = false;
        logger.debug("LocalFileDictionaryStorage 已关闭");
    }

    /**
     * 获取内部字典对象（供内部使用）
     * 
     * @return 关键词字典实例
     */
    public KeywordDictionary getDictionary() {
        return dictionary;
    }

    private void checkInitialized() throws StorageException {
        if (!initialized) {
            throw new StorageException("storage", "not initialized",
                new IllegalStateException("存储未初始化"));
        }
    }
}
