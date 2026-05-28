package cn.nexon.zerovector.core.tree;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.util.JsonUtils;
import cn.nexon.zerovector.core.util.LLMExecutors;
import cn.nexon.zerovector.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 语义树构建器
 * 负责根据文档理解结果构建语义树结构
 */
public class TreeBuilder {
    private static final Logger logger = LoggerFactory.getLogger(TreeBuilder.class);
    private static final int CLUSTERING_THRESHOLD = 5;
    
    private final LLMProvider llmProvider;
    private final KeywordDictionary dictionary;
    private final HookExecutor hookExecutor;
    private final ExecutorService executor;

    public TreeBuilder(LLMProvider llmProvider, KeywordDictionary dictionary, ConcurrencyProperties concurrencyConfig) {
        this(llmProvider, dictionary, concurrencyConfig, new DefaultHookExecutor());
    }

    public TreeBuilder(LLMProvider llmProvider, KeywordDictionary dictionary, ConcurrencyProperties concurrencyConfig, HookExecutor hookExecutor) {
        this.llmProvider = llmProvider;
        this.dictionary = dictionary;
        this.hookExecutor = Objects.requireNonNullElse(hookExecutor, new DefaultHookExecutor());
        this.executor = LLMExecutors.create(concurrencyConfig);
    }

    /**
     * 构建语义树
     *
     * @param comprehendResultMap 文档理解结果映射
     * @param chunks 文档块映射
     * @return 树构建结果，包含语义树和 LLM 调用统计
     */
    public TreeBuildResult build(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunks) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats stats = new LLMUsageStats();
        
        TreeBuildInternalResult result = buildRecursiveWithNodes("Root", comprehendResultMap, stats);
        TreeNode root = result.root();
        
        long duration = System.currentTimeMillis() - startTime;
        hookExecutor.executeHooks(HookType.TREE_BUILD_END,
            HookContext.builder(HookType.TREE_BUILD_END)
                .data("documentCount", comprehendResultMap.size())
                .data("nodeCount", result.nodes().size())
                .data("llmUsageStats", stats)
                .durationMillis(duration)
        );
        
        SemanticTree tree = new SemanticTree(root, result.nodes(), chunks);
        return new TreeBuildResult(tree, stats);
    }

    /**
     * 更新语义树
     * 在现有语义树的基础上增量添加新文档，优化树结构
     * 
     * @param existingTree 现有的语义树
     * @param newResults 新文档的理解结果列表
     * @param newChunks 新文档的文档块映射表
     * @return 树构建结果，包含更新后的语义树和 LLM 调用统计
     */
    public TreeBuildResult updateTree(SemanticTree existingTree, List<DocumentComprehendResult> newResults,
                                       Map<String, DocumentChunk> newChunks) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats stats = new LLMUsageStats();

        if (existingTree == null || existingTree.rootNode() == null) {
            return buildNewTree(newResults, newChunks);
        }

        Map<String, TreeNode> updatedNodes = new HashMap<>(existingTree.nodes());
        Map<String, DocumentChunk> updatedChunks = new HashMap<>(existingTree.chunks());
        updatedChunks.putAll(newChunks);

        List<Map.Entry<String, DocumentChunk>> chunkEntries = new ArrayList<>(newChunks.entrySet());
        TreeNode currentRoot = existingTree.rootNode();

        for (int i = 0; i < newResults.size(); i++) {
            if (i >= chunkEntries.size()) break;
            DocumentComprehendResult result = newResults.get(i);
            Map.Entry<String, DocumentChunk> entry = chunkEntries.get(i);
            String chunkId = entry.getKey();

            stats.merge(result.llmUsageStats());
            TreeNode targetNode = findBestNodeForDocument(currentRoot, existingTree, result);

            if (targetNode != null && targetNode.isLeaf()) {
                if (targetNode.chunkIds().isEmpty()) {
                    fillEmptyLeaf(updatedNodes, targetNode, chunkId, result);
                } else {
                    TreeNode newRoot = splitLeafIntoCategory(updatedNodes, existingTree, targetNode, chunkId, result, currentRoot);
                    if (newRoot != null) currentRoot = newRoot;
                }
            } else if (targetNode != null) {
                addToCategory(updatedNodes, targetNode, chunkId, result);
            } else {
                currentRoot = addAsRootChild(updatedNodes, currentRoot, chunkId, result);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        hookExecutor.executeHooks(HookType.TREE_BUILD_END,
            HookContext.builder(HookType.TREE_BUILD_END)
                .data("documentCount", updatedChunks.size())
                .data("nodeCount", updatedNodes.size())
                .data("llmUsageStats", stats)
                .durationMillis(duration)
        );

        SemanticTree tree = new SemanticTree(currentRoot, updatedNodes, updatedChunks);
        return new TreeBuildResult(tree, stats);
    }

    private TreeBuildResult buildNewTree(List<DocumentComprehendResult> newResults, Map<String, DocumentChunk> newChunks) {
        Map<String, DocumentComprehendResult> resultMap = new HashMap<>();
        List<String> chunkIdList = new ArrayList<>(newChunks.keySet());
        for (int i = 0; i < newResults.size(); i++) {
            resultMap.put(chunkIdList.get(i), newResults.get(i));
        }
        return build(resultMap, newChunks);
    }

    private void fillEmptyLeaf(Map<String, TreeNode> nodes, TreeNode leaf, String chunkId,
                                DocumentComprehendResult result) {
        List<String> allKeywords = new ArrayList<>(leaf.keywords());
        List<String> docKeywords = result.keywordDefinitions().stream()
            .map(KeywordDefinition::keyword).collect(Collectors.toList());
        allKeywords.addAll(docKeywords);
        List<String> allEntities = new ArrayList<>(leaf.keyEntities());
        allEntities.addAll(result.entities());
        List<String> allExamples = new ArrayList<>(leaf.exampleQuestions());
        allExamples.addAll(result.exampleQuestions());

        dictionary.addEntries(docKeywords, leaf.id());

        nodes.put(leaf.id(), new TreeNode(leaf.id(), leaf.name(), leaf.description(),
            NodeType.LEAF, leaf.childrenIds(), List.of(chunkId), allEntities, allKeywords, allExamples));
    }

    private TreeNode splitLeafIntoCategory(Map<String, TreeNode> nodes, SemanticTree existingTree,
                                            TreeNode leaf, String chunkId, DocumentComprehendResult result,
                                            TreeNode currentRoot) {
        TreeNode newLeafNode = createLeafNodeForDocument(chunkId, result);
        nodes.put(newLeafNode.id(), newLeafNode);

        List<String> childrenIds = new ArrayList<>();
        String originalChunkId = leaf.chunkIds().get(0);
        DocumentChunk originalChunk = existingTree.chunks().get(originalChunkId);
        if (originalChunk != null) {
            TreeNode originalLeaf = createLeafNodeForDocument(originalChunkId, originalChunk);
            nodes.put(originalLeaf.id(), originalLeaf);
            childrenIds.add(originalLeaf.id());
        }
        childrenIds.add(newLeafNode.id());

        List<String> allKeywords = new ArrayList<>(leaf.keywords());
        List<String> docKeywords = result.keywordDefinitions().stream()
            .map(KeywordDefinition::keyword).collect(Collectors.toList());
        allKeywords.addAll(docKeywords);
        dictionary.addEntries(docKeywords, leaf.id());

        TreeNode category = new TreeNode(leaf.id(), leaf.name(),
            "Category node containing " + (leaf.chunkIds().size() + 1) + " documents",
            NodeType.CATEGORY, childrenIds, List.of(), leaf.keyEntities(), allKeywords, leaf.exampleQuestions());
        nodes.put(leaf.id(), category);

        return leaf.id().equals(currentRoot.id()) ? category : null;
    }

    private void addToCategory(Map<String, TreeNode> nodes, TreeNode category, String chunkId,
                                DocumentComprehendResult result) {
        TreeNode newLeaf = createLeafNodeForDocument(chunkId, result);
        nodes.put(newLeaf.id(), newLeaf);

        List<String> newChildrenIds = new ArrayList<>(category.childrenIds());
        newChildrenIds.add(newLeaf.id());

        nodes.put(category.id(), new TreeNode(category.id(), category.name(), category.description(),
            NodeType.CATEGORY, newChildrenIds, category.chunkIds(),
            category.keyEntities(), category.keywords(), category.exampleQuestions()));
    }

    private TreeNode addAsRootChild(Map<String, TreeNode> nodes, TreeNode root, String chunkId,
                                     DocumentComprehendResult result) {
        TreeNode newLeaf = createLeafNodeForDocument(chunkId, result);
        nodes.put(newLeaf.id(), newLeaf);

        List<String> newChildrenIds = new ArrayList<>(root.childrenIds());
        newChildrenIds.add(newLeaf.id());
        List<String> newChunkIds = new ArrayList<>(root.chunkIds());
        newChunkIds.add(chunkId);

        TreeNode updatedRoot = new TreeNode(root.id(), root.name(), root.description(),
            NodeType.CATEGORY, newChildrenIds, newChunkIds,
            root.keyEntities(), root.keywords(), root.exampleQuestions());
        nodes.put(root.id(), updatedRoot);
        return updatedRoot;
    }

    private TreeBuildInternalResult buildRecursiveWithNodes(String name, Map<String, DocumentComprehendResult> documents, LLMUsageStats stats) {
        Map<String, TreeNode> allNodes = new ConcurrentHashMap<>();
        
        if (documents.size() <= CLUSTERING_THRESHOLD) {
            return buildSmallDocumentSet(name, documents, allNodes, stats);
        }
        
        return buildLargeDocumentSet(name, documents, allNodes, stats);
    }

    private TreeBuildInternalResult buildSmallDocumentSet(String name, Map<String, DocumentComprehendResult> documents, Map<String, TreeNode> allNodes, LLMUsageStats stats) {
        try {
            List<NodeCategory> categories = performDocumentClustering(documents, stats);
            
            if (shouldCreateSingleLeafNode(categories, documents)) {
                return createSingleLeafNodeResult(name, categories.get(0).getDocuments(), allNodes);
            }
            
            if (categories.size() > 1) {
                return buildMultiCategoryTree(name, documents, categories, allNodes, stats);
            }
        } catch (Exception e) {
            logger.warn("聚类失败，创建叶子节点: {}", e.getMessage());
        }
        
        return createSingleLeafNodeResult(name, documents, allNodes);
    }

    private TreeBuildInternalResult buildLargeDocumentSet(String name, Map<String, DocumentComprehendResult> documents, Map<String, TreeNode> allNodes, LLMUsageStats stats) {
        List<NodeCategory> categories = performDocumentClustering(documents, stats);
        return buildMultiCategoryTree(name, documents, categories, allNodes, stats);
    }

    private List<NodeCategory> performDocumentClustering(Map<String, DocumentComprehendResult> documents, LLMUsageStats stats) {
        List<String> summaries = documents.values().stream()
            .map(DocumentComprehendResult::summary)
            .toList();
        
        String clusterPrompt = LLMPromptTemplates.clusterDocumentChunks(summaries);
        LLMResponse response = llmProvider.chat(clusterPrompt, SmartCacheStrategy.RequestType.CLUSTER_DOCUMENTS);
        stats.add(response);
        
        logger.debug("聚类操作完成");
        
        return parseClusterResponse(response.content(), documents);
    }

    private boolean shouldCreateSingleLeafNode(List<NodeCategory> categories, Map<String, DocumentComprehendResult> documents) {
        return categories.size() == 1 && categories.get(0).getDocuments().size() == documents.size();
    }

    private TreeBuildInternalResult createSingleLeafNodeResult(String name, Map<String, DocumentComprehendResult> documents, Map<String, TreeNode> allNodes) {
        TreeNode leafNode = createLeafNode(name, documents);
        allNodes.put(leafNode.id(), leafNode);
        return new TreeBuildInternalResult(leafNode, allNodes);
    }

    private TreeBuildInternalResult buildMultiCategoryTree(String name, Map<String, DocumentComprehendResult> documents, List<NodeCategory> categories, Map<String, TreeNode> allNodes, LLMUsageStats stats) {
        List<String> childIds = new ArrayList<>();
        
        for (NodeCategory cat : categories) {
            processCategory(cat, allNodes, childIds, stats);
        }
        
        TreeNode categoryNode = buildCategoryNode(name, documents, categories.size(), childIds, stats);
        allNodes.put(categoryNode.id(), categoryNode);
        
        return new TreeBuildInternalResult(categoryNode, allNodes);
    }

    private void processCategory(NodeCategory cat, Map<String, TreeNode> allNodes, List<String> childIds, LLMUsageStats stats) {
        String summariesText = StringUtils.joinWithSpace(
            cat.getDocuments().values().stream()
                .map(DocumentComprehendResult::summary)
                .toList()
        );
        
        NodeMetadata metadata = extractNodeMetadata(summariesText, stats);
        
        logger.debug("关键词提取完成, 节点: {}", cat.getName());
        
        dictionary.addEntries(metadata.keywords(), cat.getNodeId());
        
        TreeBuildInternalResult childResult = buildRecursiveWithNodes(cat.getName(), cat.getDocuments(), stats);
        TreeNode childNode = childResult.root();
        childIds.add(childNode.id());
        
        allNodes.putAll(childResult.nodes());
    }

    private TreeNode buildCategoryNode(String name, Map<String, DocumentComprehendResult> documents, int categoryCount, List<String> childIds, LLMUsageStats stats) {
        String nodeId = UUID.randomUUID().toString();
        String allSummaries = StringUtils.joinWithSpace(
            documents.values().stream()
                .map(DocumentComprehendResult::summary)
                .toList()
        );
        
        NodeMetadata metadata = extractNodeMetadata(allSummaries, stats);
        
        logger.debug("节点关键词提取完成");
        
        dictionary.addEntries(metadata.keywords(), nodeId);
        
        return new TreeNode(
            nodeId,
            name,
            "Category node containing " + categoryCount + " subcategories",
            NodeType.CATEGORY,
            childIds,
            List.of(),
            metadata.entities(),
            metadata.keywords(),
            metadata.examples()
        );
    }

    private NodeMetadata extractNodeMetadata(String summariesText, LLMUsageStats stats) {
        String keywordsPrompt = LLMPromptTemplates.extractKeywords(summariesText);
        String entitiesPrompt = LLMPromptTemplates.extractEntities(summariesText);
        String examplesPrompt = LLMPromptTemplates.generateExampleQuestions(summariesText);

        CompletableFuture<LLMResponse> keywordsFuture = CompletableFuture.supplyAsync(
            () -> llmProvider.chat(keywordsPrompt, SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS), executor);
        CompletableFuture<LLMResponse> entitiesFuture = CompletableFuture.supplyAsync(
            () -> llmProvider.chat(entitiesPrompt, SmartCacheStrategy.RequestType.EXTRACT_ENTITIES), executor);
        CompletableFuture<LLMResponse> examplesFuture = CompletableFuture.supplyAsync(
            () -> llmProvider.chat(examplesPrompt, SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS), executor);

        CompletableFuture.allOf(keywordsFuture, entitiesFuture, examplesFuture).join();

        LLMResponse keywordsResponse = keywordsFuture.join();
        LLMResponse entitiesResponse = entitiesFuture.join();
        LLMResponse examplesResponse = examplesFuture.join();

        stats.add(keywordsResponse);
        stats.add(entitiesResponse);
        stats.add(examplesResponse);

        List<String> keywords = parseListResponse(keywordsResponse.content());
        List<String> entities = parseListResponse(entitiesResponse.content());
        List<String> examples = parseListResponse(examplesResponse.content());

        return new NodeMetadata(keywords, entities, examples);
    }

    private record NodeMetadata(List<String> keywords, List<String> entities, List<String> examples) {}

    private TreeNode createLeafNode(String name, Map<String, DocumentComprehendResult> documents) {
        String nodeId = UUID.randomUUID().toString();
        List<String> docIds = new ArrayList<>(documents.keySet());
        
        List<String> allKeywords = new ArrayList<>();
        List<String> allEntities = new ArrayList<>();
        List<String> allExamples = new ArrayList<>();
        
        for (DocumentComprehendResult result : documents.values()) {
            List<String> keywords = result.keywordDefinitions().stream()
                .map(kd -> kd.keyword())
                .collect(Collectors.toList());
            
            allKeywords.addAll(keywords);
            allEntities.addAll(result.entities());
            allExamples.addAll(result.exampleQuestions());
        }
        
        dictionary.addEntries(allKeywords, nodeId);
        
        return new TreeNode(
            nodeId,
            name,
            "Leaf node containing " + documents.size() + " documents",
            NodeType.LEAF,
            List.of(),
            docIds,
            allEntities,
            allKeywords,
            allExamples
        );
    }

    private TreeNode createLeafNodeForDocument(String docId, DocumentComprehendResult result) {
        String nodeId = UUID.randomUUID().toString();
        
        List<String> keywords = result.keywordDefinitions().stream()
            .map(kd -> kd.keyword())
            .collect(Collectors.toList());
        
        dictionary.addEntries(keywords, nodeId);
        
        return new TreeNode(
            nodeId,
            StringUtils.truncate(result.summary(), 50),
            "Document: " + docId,
            NodeType.LEAF,
            List.of(),
            List.of(docId),
            result.entities(),
            keywords,
            result.exampleQuestions()
        );
    }

    private TreeNode createLeafNodeForDocument(String docId, DocumentChunk chunk) {
        String nodeId = UUID.randomUUID().toString();
        
        return new TreeNode(
            nodeId,
            StringUtils.truncate(chunk.summary(), 50),
            "Document: " + docId,
            NodeType.LEAF,
            List.of(),
            List.of(docId),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private TreeNode findBestNodeForDocument(TreeNode currentNode, SemanticTree tree, DocumentComprehendResult result) {
        if (currentNode == null) {
            return null;
        }
        
        if (currentNode.isLeaf()) {
            return currentNode;
        }
        
        List<String> docKeywords = result.keywordDefinitions().stream()
            .map(kd -> kd.keyword().toLowerCase())
            .collect(Collectors.toList());
        
        if (currentNode.childrenIds().isEmpty()) {
            return currentNode;
        }

        Map.Entry<TreeNode, Integer> best = currentNode.childrenIds().stream()
            .map(tree::getNode)
            .filter(Objects::nonNull)
            .map(child -> Map.entry(child, countKeywordMatches(docKeywords, child)))
            .max(Map.Entry.comparingByValue())
            .orElse(null);

        if (best != null && best.getValue() > 0) {
            return findBestNodeForDocument(best.getKey(), tree, result);
        }
        return currentNode;
    }

    private int countKeywordMatches(List<String> docKeywords, TreeNode node) {
        int matchCount = 0;
        
        for (String nodeKeyword : node.keywords()) {
            if (docKeywords.contains(nodeKeyword.toLowerCase())) {
                matchCount++;
            }
        }
        
        for (String entity : node.keyEntities()) {
            for (String docKeyword : docKeywords) {
                if (docKeyword.contains(entity.toLowerCase()) || entity.toLowerCase().contains(docKeyword)) {
                    matchCount++;
                    break;
                }
            }
        }
        
        return matchCount;
    }

    public record TreeBuildResult(
        SemanticTree tree,
        LLMUsageStats llmUsageStats
    ) {}
    
    private record TreeBuildInternalResult(
        TreeNode root,
        Map<String, TreeNode> nodes
    ) {}
    
    private List<NodeCategory> parseClusterResponse(String response, Map<String, DocumentComprehendResult> documents) {
        try {
            ClusterResponse result = JsonUtils.parseJson(response, ClusterResponse.class);
            
            List<NodeCategory> categories = new ArrayList<>();
            List<String> docIds = new ArrayList<>(documents.keySet());
            
            for (Cluster cluster : result.clusters()) {
                Map<String, DocumentComprehendResult> categoryDocs = new HashMap<>();
                for (int i = 0; i < cluster.chunkIndices().size(); i++) {
                    int chunkIndex = cluster.chunkIndices().get(i);
                    if (chunkIndex >= 0 && chunkIndex < docIds.size()) {
                        String docId = docIds.get(chunkIndex);
                        categoryDocs.put(docId, documents.get(docId));
                    } else {
                        logger.warn("聚类索引 {} 超出范围 [0, {})，跳过", chunkIndex, docIds.size() - 1);
                    }
                }
                if (!categoryDocs.isEmpty()) {
                    categories.add(new NodeCategory(UUID.randomUUID().toString(), cluster.name(), categoryDocs));
                }
            }
            
            if (categories.isEmpty()) {
                logger.warn("解析聚类响应后没有有效类别，使用默认分类");
                return createDefaultCategories(documents);
            }
            
            return categories;
        } catch (Exception e) {
            logger.error("解析聚类响应失败: {}", e.getMessage());
            return createDefaultCategories(documents);
        }
    }
    
    private List<String> parseListResponse(String response) {
        return JsonUtils.parseStringList(response);
    }
    
    private List<NodeCategory> createDefaultCategories(Map<String, DocumentComprehendResult> documents) {
        List<NodeCategory> categories = new ArrayList<>();
        if (documents.size() <= 3) {
            int i = 0;
            for (Map.Entry<String, DocumentComprehendResult> entry : documents.entrySet()) {
                Map<String, DocumentComprehendResult> categoryDocs = new HashMap<>();
                categoryDocs.put(entry.getKey(), entry.getValue());
                categories.add(new NodeCategory(UUID.randomUUID().toString(), "文档 " + (i + 1), categoryDocs));
                i++;
            }
        } else {
            int numCategories = Math.min(3, documents.size());
            int docsPerCategory = Math.max(1, documents.size() / numCategories);
            List<String> docIds = new ArrayList<>(documents.keySet());
            for (int i = 0; i < numCategories; i++) {
                int startIndex = i * docsPerCategory;
                int endIndex = Math.min(startIndex + docsPerCategory, docIds.size());
                Map<String, DocumentComprehendResult> categoryDocs = new HashMap<>();
                for (int j = startIndex; j < endIndex; j++) {
                    categoryDocs.put(docIds.get(j), documents.get(docIds.get(j)));
                }
                categories.add(new NodeCategory(UUID.randomUUID().toString(), "类别 " + (i + 1), categoryDocs));
            }
        }
        return categories;
    }
    
    private record ClusterResponse(List<Cluster> clusters) {}
    private record Cluster(String name, List<Integer> chunkIndices) {}
    
    public static class NodeCategory {
        private final String nodeId;
        private final String name;
        private final Map<String, DocumentComprehendResult> documents;
        
        public NodeCategory(String nodeId, String name, Map<String, DocumentComprehendResult> documents) {
            this.nodeId = nodeId;
            this.name = name;
            this.documents = documents;
        }
        
        public String getNodeId() { return nodeId; }
        public String getName() { return name; }
        public Map<String, DocumentComprehendResult> getDocuments() { return documents; }
    }
}
