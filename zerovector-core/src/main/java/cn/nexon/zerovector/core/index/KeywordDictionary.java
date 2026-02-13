package cn.nexon.zerovector.core.index;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键词倒排索引
 * [CRITICAL] 必须支持高并发读取
 */
public final class KeywordDictionary {
    
    // 倒排索引：Keyword -> List<NodeId>
    private final Map<String, List<String>> invertedIndex = new ConcurrentHashMap<>();
    
    // 权重索引：Keyword -> Weight (用于排序)
    private final Map<String, Double> keywordWeights = new ConcurrentHashMap<>();

    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 添加关键词条目
     */
    public void addEntry(String keyword, String nodeId, double weight) {
        String key = keyword.toLowerCase();
        invertedIndex.compute(key, (k, list) -> {
            if (list == null) {
                list = new ArrayList<>();
            }
            list.add(nodeId);
            return list;
        });
        keywordWeights.put(key, weight);
    }
    
    /**
     * 批量添加关键词条目
     */
    public void addEntries(List<String> keywords, String nodeId) {
        for (String keyword : keywords) {
            addEntry(keyword, nodeId, 1.0); // 默认权重为1.0
        }
    }
    
    /**
     * 匹配查询中的关键词，返回候选节点
     * [KEY] 混合检索的第一步
     */
    public Map<String, Double> matchCandidates(String query) {
        Map<String, Double> candidateScores = new HashMap<>();
        
        // 简单分词匹配 (可升级为 AC 自动机或 FST)
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
     * 获取最高权重的候选节点
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
     */
    public void saveToFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        // 创建包含所有数据的映射
        Map<String, Object> data = new HashMap<>();
        data.put("invertedIndex", invertedIndex);
        data.put("keywordWeights", keywordWeights);

        // 使用 Jackson 序列化为 JSON
        String json = objectMapper.writeValueAsString(data);
        Files.writeString(path, json);
    }

    /**
     * 从文件加载关键词字典
     */
    public static KeywordDictionary loadFromFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            return new KeywordDictionary();
        }

        String json = Files.readString(path);
        Map<String, Object> data = objectMapper.readValue(json, Map.class);

        KeywordDictionary dictionary = new KeywordDictionary();

        // 恢复倒排索引
        Map<String, List<String>> invertedIndexData = (Map<String, List<String>>) data.get("invertedIndex");
        if (invertedIndexData != null) {
            dictionary.invertedIndex.putAll(invertedIndexData);
        }

        // 恢复权重索引
        Map<String, Double> keywordWeightsData = (Map<String, Double>) data.get("keywordWeights");
        if (keywordWeightsData != null) {
            dictionary.keywordWeights.putAll(keywordWeightsData);
        }

        return dictionary;
    }
    
    /**
     * 检查是否包含指定关键词
     */
    public boolean containsKeyword(String keyword) {
        return invertedIndex.containsKey(keyword.toLowerCase());
    }
    
    /**
     * 获取关键词的所有节点
     */
    public List<String> getNodesForKeyword(String keyword) {
        return invertedIndex.getOrDefault(keyword.toLowerCase(), Collections.emptyList());
    }
    
    /**
     * 获取所有关键词
     */
    public Set<String> getAllKeywords() {
        return new HashSet<>(invertedIndex.keySet());
    }
    
    /**
     * 清空词典
     */
    public void clear() {
        invertedIndex.clear();
        keywordWeights.clear();
    }
    
    /**
     * 获取词典大小
     */
    public int size() {
        return invertedIndex.size();
    }
}