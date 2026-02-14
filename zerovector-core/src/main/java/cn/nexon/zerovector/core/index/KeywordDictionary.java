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

    public void addKeywordDefinition(KeywordDefinition definition) {
        String normalizedKeyword = definition.normalizedKeyword();
        keywordDefinitions.put(normalizedKeyword, definition);
    }

    public void addKeywordDefinitions(List<KeywordDefinition> definitions) {
        for (KeywordDefinition definition : definitions) {
            addKeywordDefinition(definition);
        }
    }

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

    public void addEntries(List<String> keywords, String nodeId) {
        for (String keyword : keywords) {
            addEntry(keyword, nodeId, 1.0);
        }
    }

    public void addEntriesWithDefinitions(List<KeywordDefinition> definitions, String nodeId) {
        for (KeywordDefinition definition : definitions) {
            addKeywordDefinition(definition);
            addEntry(definition.keyword(), nodeId, 1.0);
        }
    }

    public Optional<KeywordDefinition> getKeywordDefinition(String keyword) {
        return Optional.ofNullable(keywordDefinitions.get(keyword.toLowerCase()));
    }

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

    public Optional<String> getTopCandidate(String query) {
        Map<String, Double> candidates = matchCandidates(query);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        
        return candidates.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }

    public void saveToFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        
        Map<String, Object> data = new HashMap<>();
        data.put("keywordDefinitions", keywordDefinitions);
        data.put("invertedIndex", invertedIndex);
        data.put("keywordWeights", keywordWeights);
        
        String json = objectMapper.writeValueAsString(data);
        Files.writeString(path, json);
        
        logger.info("保存关键词字典: {}", filePath);
        logger.info("关键词定义数量: {}", keywordDefinitions.size());
        logger.info("倒排索引数量: {}", invertedIndex.size());
        logger.info("关键词权重数量: {}", keywordWeights.size());
    }

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

    public void clear() {
        keywordDefinitions.clear();
        invertedIndex.clear();
        keywordWeights.clear();
    }

    public int size() {
        return keywordDefinitions.size();
    }
}
