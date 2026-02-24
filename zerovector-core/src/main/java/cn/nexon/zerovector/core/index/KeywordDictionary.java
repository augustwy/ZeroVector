package cn.nexon.zerovector.core.index;

import cn.nexon.zerovector.core.model.KeywordDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KeywordDictionary {
    
    private final Map<String, KeywordDefinition> keywordDefinitions = new ConcurrentHashMap<>();
    private final Map<String, List<String>> invertedIndex = new ConcurrentHashMap<>();
    private final Map<String, Double> keywordWeights = new ConcurrentHashMap<>();

    private final Logger logger = LoggerFactory.getLogger(KeywordDictionary.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 添加关键词定义
     * 将关键词定义添加到字典中
     * 
     * @param definition 关键词定义对象
     */
    public void addKeywordDefinition(KeywordDefinition definition) {
        String normalizedKeyword = definition.normalizedKeyword();
        keywordDefinitions.put(normalizedKeyword, definition);
    }

    /**
     * 批量添加关键词定义
     * 
     * @param definitions 关键词定义列表
     */
    public void addKeywordDefinitions(List<KeywordDefinition> definitions) {
        for (KeywordDefinition definition : definitions) {
            addKeywordDefinition(definition);
        }
    }

    /**
     * 添加关键词条目
     * 将关键词与节点ID关联，并设置权重
     * 
     * @param keyword 关键词
     * @param nodeId 节点ID
     * @param weight 关键词权重
     */
    public void addEntry(String keyword, String nodeId, double weight) {
        String normalizedKeyword = keyword.toLowerCase();
        invertedIndex.compute(normalizedKeyword, (k, list) -> {
            if (list == null) {
                list = new ArrayList<>();
            }
            if (!list.contains(nodeId)) {
                list.add(nodeId);
            }
            return list;
        });
        keywordWeights.merge(normalizedKeyword, weight, Double::sum);
    }

    /**
     * 批量添加关键词条目
     * 将多个关键词与同一个节点ID关联
     * 
     * @param keywords 关键词列表
     * @param nodeId 节点ID
     */
    public void addEntries(List<String> keywords, String nodeId) {
        for (String keyword : keywords) {
            addEntry(keyword, nodeId, 1.0);
        }
    }

    /**
     * 批量添加关键词条目及其定义
     * 
     * @param definitions 关键词定义列表
     * @param nodeId 节点ID
     */
    public void addEntriesWithDefinitions(List<KeywordDefinition> definitions, String nodeId) {
        for (KeywordDefinition definition : definitions) {
            addKeywordDefinition(definition);
            addEntry(definition.keyword(), nodeId, 1.0);
        }
    }

    /**
     * 获取关键词定义
     * 
     * @param keyword 关键词
     * @return 关键词定义的Optional对象，如果不存在则为空
     */
    public Optional<KeywordDefinition> getKeywordDefinition(String keyword) {
        return Optional.ofNullable(keywordDefinitions.get(keyword.toLowerCase()));
    }

    /**
     * 匹配候选节点
     * 根据查询字符串匹配相关的节点，并返回节点及其得分
     * 
     * @param query 查询字符串
     * @return 节点ID到得分的映射表
     */
    public Map<String, Double> matchCandidates(String query) {
        Map<String, Double> candidateScores = new HashMap<>();
        
        Arrays.stream(query.split("\\s+"))
            .filter(invertedIndex::containsKey)
            .forEach(kw -> {
                double weight = keywordWeights.getOrDefault(kw, 1.0);
                invertedIndex.get(kw).forEach(nodeId -> 
                    candidateScores.merge(nodeId, weight, Double::sum)
                );
            });
            
        return candidateScores;
    }

    /**
     * 获取最佳候选节点
     * 根据查询字符串返回得分最高的节点
     * 
     * @param query 查询字符串
     * @return 最佳节点ID的Optional对象，如果没有匹配则为空
     */
    public Optional<String> getTopCandidate(String query) {
        Map<String, Double> candidates = matchCandidates(query);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        
        return candidates.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }

    /**
     * 保存关键词字典到文件
     * 将关键词定义、倒排索引和权重信息保存到JSON文件
     * 
     * @param filePath 保存文件路径
     * @throws IOException 如果保存文件失败
     */
    public void saveToFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        
        Map<String, Object> data = new HashMap<>();
        data.put("keywordDefinitions", keywordDefinitions);
        data.put("invertedIndex", invertedIndex);
        data.put("keywordWeights", keywordWeights);
        
        String json = objectMapper.writeValueAsString(data);
        Files.writeString(path, json);

        logger.debug("保存关键词字典: {}", filePath);
        logger.debug("关键词定义数量: {}", keywordDefinitions.size());
        logger.debug("倒排索引数量: {}", invertedIndex.size());
        logger.debug("关键词权重数量: {}", keywordWeights.size());
    }

    /**
     * 从文件加载关键词字典
     * 从JSON文件中加载关键词定义、倒排索引和权重信息
     * 
     * @param filePath 加载文件路径
     * @return 关键词字典实例
     * @throws IOException 如果加载文件失败
     */
    public static KeywordDictionary loadFromFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            return new KeywordDictionary();
        }

        String json = Files.readString(path);
        Map<String, Object> data = objectMapper.readValue(json, Map.class);

        KeywordDictionary dictionary = new KeywordDictionary();

        Map<String, Map<String, Object>> definitionsData = (Map<String, Map<String, Object>>) data.get("keywordDefinitions");
        if (definitionsData != null) {
            definitionsData.forEach((key, value) -> {
                String keyword = (String) value.get("keyword");
                String definition = (String) value.get("definition");
                String context = (String) value.get("context");
                String documentId = (String) value.get("documentId");
                Integer frequency = (Integer) value.get("frequency");
                dictionary.keywordDefinitions.put(key, new KeywordDefinition(
                    keyword, definition, context, documentId, frequency != null ? frequency : 1
                ));
            });
        }

        Map<String, List<String>> invertedIndexData = (Map<String, List<String>>) data.get("invertedIndex");
        if (invertedIndexData != null) {
            dictionary.invertedIndex.putAll(invertedIndexData);
        }

        Map<String, Double> keywordWeightsData = (Map<String, Double>) data.get("keywordWeights");
        if (keywordWeightsData != null) {
            dictionary.keywordWeights.putAll(keywordWeightsData);
        }

        return dictionary;
    }

    public boolean containsKeyword(String keyword) {
        return keywordDefinitions.containsKey(keyword.toLowerCase());
    }

    public List<String> getNodesForKeyword(String keyword) {
        return invertedIndex.getOrDefault(keyword.toLowerCase(), Collections.emptyList());
    }

    public Set<String> getAllKeywords() {
        return new HashSet<>(keywordDefinitions.keySet());
    }

    public Collection<KeywordDefinition> getAllKeywordDefinitions() {
        return keywordDefinitions.values();
    }

    /**
     * 清空关键词字典
     * 删除所有关键词定义、倒排索引和权重信息
     */
    public void clear() {
        keywordDefinitions.clear();
        invertedIndex.clear();
        keywordWeights.clear();
    }

    /**
     * 获取关键词字典大小
     * 
     * @return 关键词定义的数量
     */
    public int size() {
        return keywordDefinitions.size();
    }
}
