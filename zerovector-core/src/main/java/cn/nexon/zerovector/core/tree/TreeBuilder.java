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

package cn.nexon.zerovector.core.tree;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.model.NodeType;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.model.TreeNode;
import cn.nexon.zerovector.core.util.JsonUtils;
import cn.nexon.zerovector.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 语义树构建器。
 *
 * <p>文档是聚类和检索的基本单位：
 * <ul>
 *   <li>小文档：一个 {@link NodeType#LEAF}，正文是整篇；</li>
 *   <li>大文档：一个 {@link NodeType#DOCUMENT} 节点，子节点是章节叶子。</li>
 * </ul>
 *
 * <p>递归有明确上限：单类别或达到最大深度时直接退化为分类节点，
 * 避免同一批文档反复聚类。
 */
public final class TreeBuilder {

    private static final Logger logger = LoggerFactory.getLogger(TreeBuilder.class);
    private static final int CLUSTERING_THRESHOLD = 5;
    private static final int MAX_DEPTH = 8;

    private final LLMProvider llmProvider;
    private final KeywordDictionary dictionary;
    private final ExecutorService executor;

    public TreeBuilder(LLMProvider llmProvider, KeywordDictionary dictionary, ExecutorService executor) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider");
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** 章节输入；整篇文档也使用一个 ChapterInput 表示。 */
    public record ChapterInput(String id, String title, DocumentComprehendResult result) {
    }

    /** 文档输入：result 用于聚类/文档级关键词，chapters 用于展开章节。 */
    public record DocumentInput(String id, String title, DocumentComprehendResult result,
                                List<ChapterInput> chapters) {
        public DocumentInput {
            chapters = chapters == null ? List.of() : List.copyOf(chapters);
        }
    }

    public TreeBuildResult build(List<DocumentInput> documents, Map<String, DocumentChunk> chunks) {
        if (documents == null || documents.isEmpty()) {
            return new TreeBuildResult(SemanticTree.empty(), new LLMUsageStats());
        }

        Map<String, DocumentInput> docs = new LinkedHashMap<>();
        for (DocumentInput document : documents) {
            docs.put(document.id(), document);
        }

        LLMUsageStats stats = new LLMUsageStats();
        Map<String, TreeNode> nodes = new HashMap<>();
        TreeNode root = buildRecursive("Root", docs, nodes, UUID.randomUUID().toString(), 0, stats);
        if (root != null) {
            nodes.putIfAbsent(root.id(), root);
        }
        return new TreeBuildResult(new SemanticTree(root, nodes, chunks != null ? chunks : Map.of()), stats);
    }

    /**
     * 增量更新：新文档挂到最匹配的分类节点；已有叶子会提升为分类节点。
     */
    public TreeBuildResult updateTree(SemanticTree existingTree, List<DocumentInput> newDocuments,
                                      Map<String, DocumentChunk> newChunks) {
        if (existingTree == null || existingTree.rootNode() == null
                || existingTree.nodes().isEmpty()) {
            return build(newDocuments, newChunks);
        }
        if (newDocuments == null || newDocuments.isEmpty()) {
            return new TreeBuildResult(existingTree, new LLMUsageStats());
        }

        LLMUsageStats stats = new LLMUsageStats();
        Map<String, TreeNode> nodes = new HashMap<>(existingTree.nodes());
        Map<String, DocumentChunk> chunks = new HashMap<>(existingTree.chunks());
        if (newChunks != null) {
            chunks.putAll(newChunks);
        }

        TreeNode root = existingTree.rootNode();
        nodes.putIfAbsent(root.id(), root);
        for (DocumentInput document : newDocuments) {
            stats.merge(document.result().llmUsageStats());
            TreeNode category = findBestCategory(root, nodes, document.result());
            if (category == null) {
                root = promoteRootToCategory(nodes, root, document);
            } else {
                addDocumentToCategory(nodes, category, document);
                if (category.id().equals(root.id())) {
                    // addDocumentToCategory 会用新 children 替换节点，根引用必须同步
                    root = nodes.get(root.id());
                }
            }
        }
        return new TreeBuildResult(new SemanticTree(root, nodes, chunks), stats);
    }

    private TreeNode buildRecursive(String name, Map<String, DocumentInput> documents,
                                    Map<String, TreeNode> allNodes, String rootId, int depth,
                                    LLMUsageStats stats) {
        if (documents.isEmpty()) {
            return null;
        }
        if (documents.size() == 1) {
            return createDocumentNode(rootId, documents.values().iterator().next(), allNodes);
        }

        if (depth >= MAX_DEPTH) {
            logger.warn("语义树达到最大深度 {}，剩余 {} 个文档合并为分类节点", MAX_DEPTH, documents.size());
            return createGroupNode(rootId, name, documents, allNodes, stats);
        }

        List<NodeCategory> categories = performDocumentClustering(documents, stats);
        if (categories.isEmpty() || categories.size() == 1) {
            String groupName = categories.isEmpty() ? name : categories.get(0).name();
            return createGroupNode(rootId, groupName, documents, allNodes, stats);
        }

        return buildMultiCategoryTree(rootId, name, documents, categories, allNodes, depth, stats);
    }

    private TreeNode buildMultiCategoryTree(String rootId, String name,
                                            Map<String, DocumentInput> documents,
                                            List<NodeCategory> categories,
                                            Map<String, TreeNode> allNodes, int depth,
                                            LLMUsageStats stats) {
        List<String> childIds = new ArrayList<>();
        for (NodeCategory category : categories) {
            String childId = UUID.randomUUID().toString();
            TreeNode child = buildRecursive(category.name(), category.documents(), allNodes,
                    childId, depth + 1, stats);
            if (child != null) {
                allNodes.putIfAbsent(child.id(), child);
                childIds.add(child.id());
            }
        }

        NodeMetadata metadata = extractNodeMetadata(documents, stats);
        dictionary.addEntries(metadata.keywords(), rootId);

        TreeNode node = new TreeNode(rootId, name,
                "Category node containing " + childIds.size() + " subcategories",
                NodeType.CATEGORY, childIds, List.of(),
                metadata.entities(), metadata.keywords(), metadata.examples());
        allNodes.put(rootId, node);
        return node;
    }

    private TreeNode createGroupNode(String nodeId, String name,
                                     Map<String, DocumentInput> documents,
                                     Map<String, TreeNode> allNodes, LLMUsageStats stats) {
        List<String> childIds = new ArrayList<>();
        for (DocumentInput document : documents.values()) {
            String childId = UUID.randomUUID().toString();
            TreeNode child = createDocumentNode(childId, document, allNodes);
            childIds.add(child.id());
        }

        NodeMetadata metadata = extractNodeMetadata(documents, stats);
        dictionary.addEntries(metadata.keywords(), nodeId);

        TreeNode node = new TreeNode(nodeId, name,
                "Category node containing " + childIds.size() + " documents",
                NodeType.CATEGORY, childIds, List.of(),
                metadata.entities(), metadata.keywords(), metadata.examples());
        allNodes.put(nodeId, node);
        return node;
    }

    /**
     * 单个文档节点：整篇文档是叶子；多章节文档是 DOCUMENT + 章节 LEAF。
     */
    private TreeNode createDocumentNode(String nodeId, DocumentInput document,
                                        Map<String, TreeNode> allNodes) {
        List<ChapterInput> chapters = document.chapters();
        if (chapters.size() <= 1) {
            ChapterInput chapter = chapters.isEmpty() ? null : chapters.get(0);
            String chunkId = chapter != null ? chapter.id() : document.id();
            DocumentComprehendResult result = chapter != null ? chapter.result() : document.result();
            return createLeafNode(nodeId, leafName(document, chapter, result), chunkId,
                    "Document: " + document.id(), result, allNodes);
        }

        List<String> childIds = new ArrayList<>();
        for (ChapterInput chapter : chapters) {
            String childId = UUID.randomUUID().toString();
            TreeNode leaf = createLeafNode(childId, leafName(document, chapter, chapter.result()),
                    chapter.id(), "Chapter: " + chapter.id(), chapter.result(), allNodes);
            childIds.add(leaf.id());
        }

        List<String> keywords = keywords(document.result());
        dictionary.addEntries(keywords, nodeId);
        TreeNode node = new TreeNode(nodeId, document.title(),
                "Document node: " + document.id(), NodeType.DOCUMENT,
                childIds, List.of(),
                document.result().entities(), keywords, document.result().exampleQuestions());
        allNodes.put(nodeId, node);
        return node;
    }

    private TreeNode createLeafNode(String nodeId, String name, String chunkId, String description,
                                    DocumentComprehendResult result, Map<String, TreeNode> allNodes) {
        List<String> keywords = keywords(result);
        dictionary.addEntries(keywords, nodeId);
        TreeNode node = new TreeNode(nodeId, name, description, NodeType.LEAF,
                List.of(), List.of(chunkId),
                result.entities(), keywords, result.exampleQuestions());
        allNodes.put(nodeId, node);
        return node;
    }

    private String leafName(DocumentInput document, ChapterInput chapter, DocumentComprehendResult result) {
        String summary = result == null ? null : result.summary();
        if (summary != null && !summary.isBlank()) {
            return StringUtils.truncate(summary, 50);
        }
        if (chapter != null && chapter.title() != null && !chapter.title().isBlank()) {
            return StringUtils.truncate(chapter.title(), 50);
        }
        return document.title() == null || document.title().isBlank() ? document.id() : document.title();
    }

    private List<String> keywords(DocumentComprehendResult result) {
        return result.keywordDefinitions().stream()
                .map(KeywordDefinition::keyword)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<NodeCategory> performDocumentClustering(Map<String, DocumentInput> documents,
                                                         LLMUsageStats stats) {
        List<String> summaries = documents.values().stream()
                .map(document -> document.result().summary() == null ? "" : document.result().summary())
                .toList();

        String prompt = LLMPromptTemplates.clusterDocumentChunks(summaries);
        LLMResponse response;
        try {
            response = llmProvider.chat(prompt, SmartCacheStrategy.RequestType.CLUSTER_DOCUMENTS);
        } catch (RuntimeException e) {
            logger.warn("文档聚类调用异常，使用默认分类", e);
            return createDefaultCategories(documents);
        }
        stats.add(response);
        if (response == null || !response.success()) {
            logger.warn("文档聚类失败，使用默认分类: {}",
                    response == null ? "null response" : response.errorMessage());
            return createDefaultCategories(documents);
        }

        return parseClusterResponse(response.content(), documents);
    }

    private List<NodeCategory> parseClusterResponse(String response,
                                                    Map<String, DocumentInput> documents) {
        List<String> docIds = new ArrayList<>(documents.keySet());
        try {
            ClusterResponse parsed = JsonUtils.parseJson(response, ClusterResponse.class);
            if (parsed.clusters() == null) {
                return createDefaultCategories(documents);
            }

            List<NodeCategory> categories = new ArrayList<>();
            Set<String> assigned = new HashSet<>();
            for (Cluster cluster : parsed.clusters()) {
                if (cluster == null || cluster.chunkIndices() == null) {
                    continue;
                }
                Map<String, DocumentInput> categoryDocs = new LinkedHashMap<>();
                for (Integer index : cluster.chunkIndices()) {
                    if (index == null || index < 0 || index >= docIds.size()) {
                        logger.warn("聚类索引 {} 超出范围 [0, {})，跳过", index, docIds.size());
                        continue;
                    }
                    String docId = docIds.get(index);
                    if (assigned.add(docId)) {
                        categoryDocs.put(docId, documents.get(docId));
                    }
                }
                if (!categoryDocs.isEmpty()) {
                    String name = cluster.name() == null || cluster.name().isBlank()
                            ? "类别" : cluster.name().trim();
                    categories.add(new NodeCategory(name, categoryDocs));
                }
            }

            for (String docId : docIds) {
                if (!assigned.contains(docId)) {
                    logger.warn("聚类结果遗漏文档 {}，单独成类", docId);
                    String summary = documents.get(docId).result().summary();
                    categories.add(new NodeCategory(
                            summary == null || summary.isBlank()
                                    ? "文档" : StringUtils.truncate(summary, 20),
                            Map.of(docId, documents.get(docId))));
                }
            }

            return categories.isEmpty() ? createDefaultCategories(documents) : categories;
        } catch (Exception e) {
            logger.warn("解析聚类响应失败，使用默认分类: {}", e.getMessage());
            return createDefaultCategories(documents);
        }
    }

    private List<NodeCategory> createDefaultCategories(Map<String, DocumentInput> documents) {
        List<Map.Entry<String, DocumentInput>> entries = new ArrayList<>(documents.entrySet());
        List<NodeCategory> categories = new ArrayList<>();
        if (entries.size() <= 3) {
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, DocumentInput> entry = entries.get(i);
                categories.add(new NodeCategory("文档 " + (i + 1), Map.of(entry.getKey(), entry.getValue())));
            }
            return categories;
        }

        int categoryCount = 3;
        int baseSize = entries.size() / categoryCount;
        int remainder = entries.size() % categoryCount;
        int start = 0;
        for (int i = 0; i < categoryCount; i++) {
            int size = baseSize + (i < remainder ? 1 : 0);
            Map<String, DocumentInput> docs = new LinkedHashMap<>();
            for (int j = start; j < start + size; j++) {
                docs.put(entries.get(j).getKey(), entries.get(j).getValue());
            }
            categories.add(new NodeCategory("类别 " + (i + 1), docs));
            start += size;
        }
        return categories;
    }

    private NodeMetadata extractNodeMetadata(Map<String, DocumentInput> documents,
                                             LLMUsageStats stats) {
        String summariesText = documents.values().stream()
                .map(document -> document.result().summary() == null ? "" : document.result().summary())
                .collect(Collectors.joining(" "));

        CompletableFuture<LLMResponse> keywordsFuture = CompletableFuture.supplyAsync(
                () -> llmProvider.chat(LLMPromptTemplates.extractKeywords(summariesText),
                        SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS), executor);
        CompletableFuture<LLMResponse> entitiesFuture = CompletableFuture.supplyAsync(
                () -> llmProvider.chat(LLMPromptTemplates.extractEntities(summariesText),
                        SmartCacheStrategy.RequestType.EXTRACT_ENTITIES), executor);
        CompletableFuture<LLMResponse> examplesFuture = CompletableFuture.supplyAsync(
                () -> llmProvider.chat(LLMPromptTemplates.generateExampleQuestions(summariesText),
                        SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS), executor);

        List<String> keywords = parseOrEmpty(joinQuietly(keywordsFuture), stats);
        List<String> entities = parseOrEmpty(joinQuietly(entitiesFuture), stats);
        List<String> examples = parseOrEmpty(joinQuietly(examplesFuture), stats);
        return new NodeMetadata(keywords, entities, examples);
    }

    private LLMResponse joinQuietly(CompletableFuture<LLMResponse> future) {
        try {
            return future.join();
        } catch (RuntimeException e) {
            logger.warn("节点元数据 LLM 调用失败: {}", e.getMessage());
            return LLMResponse.failure(e.getMessage());
        }
    }

    private List<String> parseOrEmpty(LLMResponse response, LLMUsageStats stats) {
        if (response == null) {
            return List.of();
        }
        stats.add(response);
        if (!response.success()) {
            logger.warn("节点元数据提取失败: {}", response.errorMessage());
            return List.of();
        }
        try {
            List<String> parsed = JsonUtils.parseStringList(response.content());
            return parsed == null ? List.of() : parsed.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (RuntimeException e) {
            logger.warn("节点元数据解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 只向下寻找分类节点，不进入文档/章节节点。 */
    private TreeNode findBestCategory(TreeNode current, Map<String, TreeNode> nodes,
                                      DocumentComprehendResult result) {
        if (current == null) {
            return null;
        }
        if (current.type() != NodeType.CATEGORY && current.type() != NodeType.ROOT) {
            return null;
        }

        List<TreeNode> categories = current.childrenIds().stream()
                .map(nodes::get)
                .filter(Objects::nonNull)
                .filter(child -> child.type() == NodeType.CATEGORY || child.type() == NodeType.ROOT)
                .toList();
        if (categories.isEmpty()) {
            return current;
        }

        List<String> docKeywords = result.keywordDefinitions().stream()
                .map(definition -> KeywordDictionary.normalize(definition.keyword()))
                .filter(Objects::nonNull)
                .toList();

        Map.Entry<TreeNode, Integer> best = categories.stream()
                .map(child -> Map.entry(child, countKeywordMatches(docKeywords, child)))
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (best != null && best.getValue() > 0) {
            return findBestCategory(best.getKey(), nodes, result);
        }
        return current;
    }

    private void addDocumentToCategory(Map<String, TreeNode> nodes, TreeNode category,
                                       DocumentInput document) {
        TreeNode newDocument = createDocumentNode(UUID.randomUUID().toString(), document, nodes);

        List<String> childrenIds = new ArrayList<>(category.childrenIds());
        childrenIds.add(newDocument.id());

        List<String> keywords = union(category.keywords(), keywords(document.result()));
        List<String> entities = union(category.keyEntities(), document.result().entities());
        List<String> examples = union(category.exampleQuestions(), document.result().exampleQuestions());
        dictionary.addEntries(keywords, category.id());

        nodes.put(category.id(), new TreeNode(category.id(), category.name(), category.description(),
                NodeType.CATEGORY, childrenIds, List.of(), entities, keywords, examples));
    }

    private TreeNode promoteRootToCategory(Map<String, TreeNode> nodes, TreeNode root,
                                           DocumentInput document) {
        TreeNode newDocument = createDocumentNode(UUID.randomUUID().toString(), document, nodes);
        String newRootId = UUID.randomUUID().toString();

        List<String> keywords = union(root.keywords(), keywords(document.result()));
        List<String> entities = union(root.keyEntities(), document.result().entities());
        List<String> examples = union(root.exampleQuestions(), document.result().exampleQuestions());
        dictionary.addEntries(keywords, newRootId);

        TreeNode category = new TreeNode(newRootId, root.name(),
                "Category node containing " + 2 + " documents",
                NodeType.CATEGORY, List.of(root.id(), newDocument.id()), List.of(),
                entities, keywords, examples);
        nodes.put(root.id(), root);
        nodes.put(newRootId, category);
        return category;
    }

    private List<String> union(List<String> first, List<String> second) {
        Set<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return new ArrayList<>(values);
    }

    private int countKeywordMatches(List<String> docKeywords, TreeNode node) {
        int matches = 0;
        for (String nodeKeyword : node.keywords()) {
            if (docKeywords.contains(KeywordDictionary.normalize(nodeKeyword))) {
                matches++;
            }
        }
        for (String entity : node.keyEntities()) {
            String normalizedEntity = KeywordDictionary.normalize(entity);
            if (normalizedEntity == null) {
                continue;
            }
            for (String docKeyword : docKeywords) {
                if (docKeyword.contains(normalizedEntity) || normalizedEntity.contains(docKeyword)) {
                    matches++;
                    break;
                }
            }
        }
        return matches;
    }

    public record TreeBuildResult(SemanticTree tree, LLMUsageStats llmUsageStats) {
    }

    private record NodeMetadata(List<String> keywords, List<String> entities, List<String> examples) {
    }

    private record NodeCategory(String name, Map<String, DocumentInput> documents) {
    }

    private record ClusterResponse(List<Cluster> clusters) {
    }

    private record Cluster(String name, List<Integer> chunkIndices) {
    }
}
