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
import cn.nexon.zerovector.core.util.JsonUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键词倒排索引。
 *
 * <p>所有写入与查询都用 {@link #normalize(String)} 归一化，避免大小写/空白
 * 造成“写得进、查不到”的不对称问题。
 */
public final class KeywordDictionary {

    private final Map<String, KeywordDefinition> keywordDefinitions = new ConcurrentHashMap<>();
    private final Map<String, List<String>> invertedIndex = new ConcurrentHashMap<>();
    private final Map<String, Double> keywordWeights = new ConcurrentHashMap<>();

    /** 序列化快照；显式建模避免反序列化时的裸 Map 强转。 */
    public record Snapshot(
            Map<String, KeywordDefinition> keywordDefinitions,
            Map<String, List<String>> invertedIndex,
            Map<String, Double> keywordWeights
    ) {
    }

    /** 归一化关键词；空白返回 {@code null}。 */
    public static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public void addKeywordDefinition(KeywordDefinition definition) {
        if (definition == null) {
            return;
        }
        String normalized = normalize(definition.keyword());
        if (normalized != null) {
            keywordDefinitions.put(normalized, definition);
        }
    }

    public void addKeywordDefinitions(List<KeywordDefinition> definitions) {
        if (definitions == null) {
            return;
        }
        definitions.forEach(this::addKeywordDefinition);
    }

    public void addEntry(String keyword, String nodeId, double weight) {
        String normalized = normalize(keyword);
        if (normalized == null || nodeId == null || nodeId.isBlank()) {
            return;
        }
        invertedIndex.compute(normalized, (key, nodes) -> {
            List<String> updated = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
            if (!updated.contains(nodeId)) {
                updated.add(nodeId);
            }
            return List.copyOf(updated);
        });
        // 同一关键词重复写入时取最大权重，避免多次更新树导致权重无限累加
        keywordWeights.merge(normalized, weight, Math::max);
    }

    public void addEntries(List<String> keywords, String nodeId) {
        if (keywords == null) {
            return;
        }
        for (String keyword : keywords) {
            addEntry(keyword, nodeId, 1.0);
        }
    }

    public void addEntriesWithDefinitions(List<KeywordDefinition> definitions, String nodeId) {
        if (definitions == null) {
            return;
        }
        for (KeywordDefinition definition : definitions) {
            addKeywordDefinition(definition);
            addEntry(definition.keyword(), nodeId, 1.0);
        }
    }

    public Optional<KeywordDefinition> getKeywordDefinition(String keyword) {
        String normalized = normalize(keyword);
        return normalized == null ? Optional.empty() : Optional.ofNullable(keywordDefinitions.get(normalized));
    }

    /**
     * 匹配候选节点。
     *
     * <p>先按空白分词做精确匹配；精确匹配失败时退化为「词典关键词被查询串包含」，
     * 以支持中文等无空格语言。
     */
    public Map<String, Double> matchCandidates(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, Double> exact = matchCandidatesFromKeywords(List.of(query.split("\\s+")));
        if (!exact.isEmpty()) {
            return exact;
        }
        return matchByContainment(query);
    }

    private Map<String, Double> matchByContainment(String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery == null || normalizedQuery.length() < 2) {
            return Map.of();
        }
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
        return candidateScores;
    }

    public Map<String, Double> matchCandidatesFromKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> candidateScores = new HashMap<>();
        for (String keyword : keywords) {
            String normalized = normalize(keyword);
            if (normalized == null) {
                continue;
            }
            List<String> nodes = invertedIndex.get(normalized);
            if (nodes == null) {
                continue;
            }
            double weight = keywordWeights.getOrDefault(normalized, 1.0);
            for (String nodeId : nodes) {
                candidateScores.merge(nodeId, weight, Double::sum);
            }
        }
        return candidateScores;
    }

    public double getKeywordWeight(String keyword) {
        String normalized = normalize(keyword);
        return normalized == null ? 0.0 : keywordWeights.getOrDefault(normalized, 0.0);
    }

    /** 原子保存：先写临时文件再替换，避免崩溃留下半截 JSON。 */
    public void saveToFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = JsonUtils.toJson(snapshot());
        Path tempPath = Paths.get(filePath + ".tmp");
        Files.writeString(tempPath, json);
        try {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static KeywordDictionary loadFromFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return new KeywordDictionary();
        }

        Snapshot snapshot;
        try {
            snapshot = JsonUtils.parseJson(Files.readString(path), Snapshot.class);
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse keyword dictionary: " + filePath, e);
        }
        if (snapshot == null) {
            throw new IOException("Empty keyword dictionary snapshot: " + filePath);
        }
        return fromSnapshot(snapshot);
    }

    public Snapshot snapshot() {
        Map<String, KeywordDefinition> definitions = new HashMap<>(keywordDefinitions);
        Map<String, List<String>> index = new HashMap<>();
        invertedIndex.forEach((key, value) -> index.put(key, List.copyOf(value)));
        return new Snapshot(definitions, index, new HashMap<>(keywordWeights));
    }

    public static KeywordDictionary fromSnapshot(Snapshot snapshot) {
        KeywordDictionary dictionary = new KeywordDictionary();
        if (snapshot == null) {
            return dictionary;
        }
        if (snapshot.keywordDefinitions() != null) {
            snapshot.keywordDefinitions().forEach((key, value) -> {
                if (key != null && value != null) {
                    dictionary.keywordDefinitions.put(key, value);
                }
            });
        }
        if (snapshot.invertedIndex() != null) {
            snapshot.invertedIndex().forEach((key, value) -> {
                if (key != null && value != null) {
                    dictionary.invertedIndex.put(key, List.copyOf(value));
                }
            });
        }
        if (snapshot.keywordWeights() != null) {
            dictionary.keywordWeights.putAll(snapshot.keywordWeights());
        }
        return dictionary;
    }

    public boolean containsKeyword(String keyword) {
        String normalized = normalize(keyword);
        return normalized != null
                && (keywordDefinitions.containsKey(normalized) || invertedIndex.containsKey(normalized));
    }

    public List<String> getNodesForKeyword(String keyword) {
        String normalized = normalize(keyword);
        return normalized == null ? List.of() : invertedIndex.getOrDefault(normalized, List.of());
    }

    public Set<String> getAllKeywords() {
        Set<String> keywords = new HashSet<>(invertedIndex.keySet());
        keywords.addAll(keywordDefinitions.keySet());
        return keywords;
    }

    public Collection<KeywordDefinition> getAllKeywordDefinitions() {
        return Collections.unmodifiableCollection(keywordDefinitions.values());
    }

    public void clear() {
        keywordDefinitions.clear();
        invertedIndex.clear();
        keywordWeights.clear();
    }

    public void removeKeywordDefinition(String keyword) {
        String normalized = normalize(keyword);
        if (normalized == null) {
            return;
        }
        keywordDefinitions.remove(normalized);
        invertedIndex.remove(normalized);
        keywordWeights.remove(normalized);
    }

    /** 关键词总数（倒排索引与定义去重后的并集）。 */
    public int size() {
        return getAllKeywords().size();
    }
}
