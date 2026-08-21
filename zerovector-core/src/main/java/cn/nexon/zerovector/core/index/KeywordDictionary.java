/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
     * <p>精确匹配失败时（典型场景：中文等无空格分词语言，整句无法命中倒排索引），
     * 退化为「词典关键词被查询串包含」的包含匹配。
     *
     * @param query 查询字符串
     * @return 节点ID到得分的映射表
     */
    public Map<String, Double> matchCandidates(String query) {
        Map<String, Double> exact = matchCandidatesFromKeywords(List.of(query.split("\\s+")));
        if (!exact.isEmpty()) {
            return exact;
        }
        return matchByContainment(query);
    }

    private Map<String, Double> matchByContainment(String query) {
        if (query == null || query.length() < 2) {
            return Map.of();
        }
        String normalizedQuery = query.toLowerCase();
        Map<String, Double> candidateScores = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : invertedIndex.entrySet()) {
            String keyword = entry.getKey();
            // 过滤单字，避免常见字（"的""是"）造成大面积误命中
            if (keyword.length() >= 2 && normalizedQuery.contains(keyword)) {
                double weight = keywordWeights.getOrDefault(keyword, 1.0);
                for (String nodeId : entry.getValue()) {
                    candidateScores.merge(nodeId, weight, Double::sum);
                }
            }
        }
        if (!candidateScores.isEmpty()) {
            logger.debug("包含匹配命中 {} 个候选节点", candidateScores.size());
        }
        return candidateScores;
    }

    /**
     * 从关键词列表匹配候选节点
     * 根据提供的关键词列表匹配相关的节点，并返回节点及其得分
     * 
     * @param keywords 关键词列表
     * @return 节点ID到得分的映射表
     */
    public Map<String, Double> matchCandidatesFromKeywords(List<String> keywords) {
        Map<String, Double> candidateScores = new HashMap<>();
        
        keywords.stream()
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
     * 删除关键词定义及其关联的倒排索引和权重
     *
     * @param keyword 要删除的关键词
     */
    public void removeKeywordDefinition(String keyword) {
        if (keyword == null) {
            return;
        }
        // 与 addEntry 的归一化保持对称，否则混合大小写关键词删除静默失败
        String normalizedKeyword = keyword.toLowerCase();
        keywordDefinitions.remove(normalizedKeyword);
        invertedIndex.remove(normalizedKeyword);
        keywordWeights.remove(normalizedKeyword);
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
