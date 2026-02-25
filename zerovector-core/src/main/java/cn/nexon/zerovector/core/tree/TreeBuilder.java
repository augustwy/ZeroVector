package cn.nexon.zerovector.core.tree;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
import cn.nexon.zerovector.core.util.JsonUtils;
import cn.nexon.zerovector.core.util.PerformanceMonitor;
import cn.nexon.zerovector.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TreeBuilder {
    private static final Logger logger = LoggerFactory.getLogger(TreeBuilder.class);
    private static final int CLUSTERING_THRESHOLD = 5;
    
    private final LLMProvider llm;
    private final KeywordDictionary dictionary;
    private final MMapDocumentStore store;
    private final HookExecutor hookExecutor;

    public TreeBuilder(LLMProvider llm, KeywordDictionary dictionary, MMapDocumentStore store, ConcurrencyProperties concurrencyConfig) {
        this(llm, dictionary, store, concurrencyConfig, new DefaultHookExecutor());
    }

    public TreeBuilder(LLMProvider llm, KeywordDictionary dictionary, MMapDocumentStore store, ConcurrencyProperties concurrencyConfig, HookExecutor hookExecutor) {
        this.llm = llm;
        this.dictionary = dictionary;
        this.store = store;
        this.hookExecutor = hookExecutor != null ? hookExecutor : new DefaultHookExecutor();
    }

    public SemanticTree build(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunks) {
        long startTime = System.currentTimeMillis();
        
        TreeBuildResult result = buildRecursiveWithNodes("Root", comprehendResultMap);
        TreeNode root = result.root();
        
        long duration = System.currentTimeMillis() - startTime;
        hookExecutor.executeHooks(HookType.TREE_BUILD_END,
            HookContext.builder(HookType.TREE_BUILD_END)
                .data("documentCount", comprehendResultMap.size())
                .data("nodeCount", result.nodes().size())
                .durationMillis(duration)
        );
        
        return new SemanticTree(root, result.nodes(), chunks);
    }

    /**
     * 更新语义树
     * 在现有语义树的基础上增量添加新文档，优化树结构
     * 
     * @param existingTree 现有的语义树
     * @param newResults 新文档的理解结果列表
     * @param newChunks 新文档的文档块映射表
     * @return 更新后的语义树
     */
    public SemanticTree updateTree(SemanticTree existingTree, List<DocumentComprehendResult> newResults, Map<String, DocumentChunk> newChunks) {
        long startTime = System.currentTimeMillis();
        
        if (existingTree == null || existingTree.rootNode() == null) {
            Map<String, DocumentComprehendResult> resultMap = new HashMap<>();
            for (int i = 0; i < newResults.size(); i++) {
                DocumentComprehendResult result = newResults.get(i);
                String chunkId = new ArrayList<>(newChunks.keySet()).get(i);
                resultMap.put(chunkId, result);
            }
            return build(resultMap, newChunks);
        }
        
        Map<String, TreeNode> updatedNodes = new HashMap<>(existingTree.nodes());
        Map<String, DocumentChunk> updatedChunks = new HashMap<>(existingTree.chunks());
        updatedChunks.putAll(newChunks);
        
        List<String> chunkIds = new ArrayList<>(newChunks.keySet());
        TreeNode currentRoot = existingTree.rootNode();
        
        for (int i = 0; i < newResults.size(); i++) {
            DocumentComprehendResult result = newResults.get(i);
            String chunkId = chunkIds.get(i);
            DocumentChunk chunk = newChunks.get(chunkId);
            
            if (chunk == null) {
                continue;
            }
            
            TreeNode targetNode = findBestNodeForDocument(currentRoot, existingTree, result);
            
            if (targetNode != null) {
                if (targetNode.isLeaf()) {
                    if (targetNode.chunkIds().isEmpty()) {
                        List<String> newChunkIds = new ArrayList<>();
                        newChunkIds.add(chunkId);
                        
                        List<String> allKeywords = new ArrayList<>(targetNode.keywords());
                        List<String> allEntities = new ArrayList<>(targetNode.keyEntities());
                        List<String> allExamples = new ArrayList<>(targetNode.exampleQuestions());
                        
                        List<String> docKeywords = result.keywordDefinitions().stream()
                            .map(kd -> kd.keyword())
                            .collect(Collectors.toList());
                        allKeywords.addAll(docKeywords);
                        allEntities.addAll(result.entities());
                        allExamples.addAll(result.exampleQuestions());
                        
                        dictionary.addEntries(docKeywords, targetNode.id());
                        
                        TreeNode updatedLeafNode = new TreeNode(
                            targetNode.id(),
                            targetNode.name(),
                            targetNode.description(),
                            NodeType.LEAF,
                            targetNode.childrenIds(),
                            newChunkIds,
                            allEntities,
                            allKeywords,
                            allExamples
                        );
                        
                        updatedNodes.put(targetNode.id(), updatedLeafNode);
                    } else {
                        TreeNode newLeafNode = createLeafNodeForDocument(chunkId, result);
                        updatedNodes.put(newLeafNode.id(), newLeafNode);
                        
                        String originalChunkId = targetNode.chunkIds().get(0);
                        DocumentChunk originalChunk = existingTree.chunks().get(originalChunkId);
                        
                        List<String> newChildrenIds = new ArrayList<>();
                        
                        if (originalChunk != null) {
                            TreeNode originalLeafNode = createLeafNodeForDocument(originalChunkId, originalChunk);
                            updatedNodes.put(originalLeafNode.id(), originalLeafNode);
                            newChildrenIds.add(originalLeafNode.id());
                        }
                        
                        newChildrenIds.add(newLeafNode.id());
                        
                        List<String> allKeywords = new ArrayList<>(targetNode.keywords());
                        List<String> docKeywords = result.keywordDefinitions().stream()
                            .map(kd -> kd.keyword())
                            .collect(Collectors.toList());
                        allKeywords.addAll(docKeywords);
                        
                        dictionary.addEntries(docKeywords, targetNode.id());
                        
                        TreeNode updatedCategoryNode = new TreeNode(
                            targetNode.id(),
                            targetNode.name(),
                            "Category node containing " + (targetNode.chunkIds().size() + 1) + " documents",
                            NodeType.CATEGORY,
                            newChildrenIds,
                            List.of(),
                            targetNode.keyEntities(),
                            allKeywords,
                            targetNode.exampleQuestions()
                        );
                        
                        updatedNodes.put(targetNode.id(), updatedCategoryNode);
                        
                        if (targetNode.id().equals(currentRoot.id())) {
                            currentRoot = updatedCategoryNode;
                        }
                    }
                } else {
                    TreeNode newLeafNode = createLeafNodeForDocument(chunkId, result);
                    updatedNodes.put(newLeafNode.id(), newLeafNode);
                    
                    List<String> newChildrenIds = new ArrayList<>(targetNode.childrenIds());
                    newChildrenIds.add(newLeafNode.id());
                    
                    TreeNode updatedCategoryNode = new TreeNode(
                        targetNode.id(),
                        targetNode.name(),
                        targetNode.description(),
                        NodeType.CATEGORY,
                        newChildrenIds,
                        targetNode.chunkIds(),
                        targetNode.keyEntities(),
                        targetNode.keywords(),
                        targetNode.exampleQuestions()
                    );
                    
                    updatedNodes.put(targetNode.id(), updatedCategoryNode);
                }
            } else {
                TreeNode newLeafNode = createLeafNodeForDocument(chunkId, result);
                updatedNodes.put(newLeafNode.id(), newLeafNode);
                
                TreeNode root = currentRoot;
                List<String> newChildrenIds = new ArrayList<>(root.childrenIds());
                newChildrenIds.add(newLeafNode.id());
                
                List<String> newChunkIds = new ArrayList<>(root.chunkIds());
                newChunkIds.add(chunkId);
                
                TreeNode updatedRoot = new TreeNode(
                    root.id(),
                    root.name(),
                    root.description(),
                    NodeType.CATEGORY,
                    newChildrenIds,
                    newChunkIds,
                    root.keyEntities(),
                    root.keywords(),
                    root.exampleQuestions()
                );
                
                updatedNodes.put(root.id(), updatedRoot);
                currentRoot = updatedRoot;
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        hookExecutor.executeHooks(HookType.TREE_BUILD_END,
            HookContext.builder(HookType.TREE_BUILD_END)
                .data("documentCount", updatedChunks.size())
                .data("nodeCount", updatedNodes.size())
                .durationMillis(duration)
        );
        
        return new SemanticTree(currentRoot, updatedNodes, updatedChunks);
    }

    private TreeBuildResult buildRecursiveWithNodes(String name, Map<String, DocumentComprehendResult> documents) {
        Map<String, TreeNode> allNodes = new ConcurrentHashMap<>();
        
        if (documents.size() <= CLUSTERING_THRESHOLD) {
            return buildSmallDocumentSet(name, documents, allNodes);
        }
        
        return buildLargeDocumentSet(name, documents, allNodes);
    }

    private TreeBuildResult buildSmallDocumentSet(String name, Map<String, DocumentComprehendResult> documents, Map<String, TreeNode> allNodes) {
        try {
            List<NodeCategory> categories = performDocumentClustering(documents);
            
            if (shouldCreateSingleLeafNode(categories, documents)) {
                return createSingleLeafNodeResult(name, categories.get(0).getDocuments(), allNodes);
            }
            
            if (categories.size() > 1) {
                return buildMultiCategoryTree(name, documents, categories, allNodes);
            }
        } catch (Exception e) {
            logger.warn("聚类失败，创建叶子节点: {}", e.getMessage());
        }
        
        return createSingleLeafNodeResult(name, documents, allNodes);
    }

    private TreeBuildResult buildLargeDocumentSet(String name, Map<String, DocumentComprehendResult> documents, Map<String, TreeNode> allNodes) {
        List<NodeCategory> categories = performDocumentClustering(documents);
        return buildMultiCategoryTree(name, documents, categories, allNodes);
    }

    private List<NodeCategory> performDocumentClustering(Map<String, DocumentComprehendResult> documents) {
        List<String> summaries = documents.values().stream()
            .map(DocumentComprehendResult::summary)
            .toList();
        
        PerformanceMonitor clusterMonitor = new PerformanceMonitor("TreeBuilder.clusterDocuments");
        clusterMonitor.start();
        String clusterPrompt = LLMPromptTemplates.clusterDocumentChunks(summaries);
        LLMResponse response = llm.clusterDocuments(clusterPrompt);
        clusterMonitor.stop();
        
        logger.debug("聚类操作耗时: {}ms", clusterMonitor.getDurationMillis());
        
        return parseClusterResponse(response.content(), documents);
    }

    private boolean shouldCreateSingleLeafNode(List<NodeCategory> categories, Map<String, DocumentComprehendResult> documents) {
        return categories.size() == 1 && categories.get(0).getDocuments().size() == documents.size();
    }

    private TreeBuildResult createSingleLeafNodeResult(String name, Map<String, DocumentComprehendResult> documents, Map<String, TreeNode> allNodes) {
        TreeNode leafNode = createLeafNode(name, documents);
        allNodes.put(leafNode.id(), leafNode);
        return new TreeBuildResult(leafNode, allNodes);
    }

    private TreeBuildResult buildMultiCategoryTree(String name, Map<String, DocumentComprehendResult> documents, List<NodeCategory> categories, Map<String, TreeNode> allNodes) {
        List<String> childIds = new ArrayList<>();
        
        for (NodeCategory cat : categories) {
            processCategory(cat, allNodes, childIds);
        }
        
        TreeNode categoryNode = buildCategoryNode(name, documents, categories.size(), childIds);
        allNodes.put(categoryNode.id(), categoryNode);
        
        return new TreeBuildResult(categoryNode, allNodes);
    }

    private void processCategory(NodeCategory cat, Map<String, TreeNode> allNodes, List<String> childIds) {
        String summariesText = StringUtils.joinWithSpace(
            cat.getDocuments().values().stream()
                .map(DocumentComprehendResult::summary)
                .toList()
        );
        
        NodeMetadata metadata = extractNodeMetadata(summariesText);
        
        logger.debug("关键词提取耗时: {}ms, 节点: {}", metadata.extractionTime(), cat.getName());
        
        dictionary.addEntries(metadata.keywords(), cat.getNodeId());
        
        TreeBuildResult childResult = buildRecursiveWithNodes(cat.getName(), cat.getDocuments());
        TreeNode childNode = childResult.root();
        childIds.add(childNode.id());
        
        allNodes.putAll(childResult.nodes());
    }

    private TreeNode buildCategoryNode(String name, Map<String, DocumentComprehendResult> documents, int categoryCount, List<String> childIds) {
        String nodeId = UUID.randomUUID().toString();
        String allSummaries = StringUtils.joinWithSpace(
            documents.values().stream()
                .map(DocumentComprehendResult::summary)
                .toList()
        );
        
        NodeMetadata metadata = extractNodeMetadata(allSummaries);
        
        logger.debug("节点关键词提取耗时: {}ms", metadata.extractionTime());
        
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

    private NodeMetadata extractNodeMetadata(String summariesText) {
        PerformanceMonitor keywordsMonitor = new PerformanceMonitor("TreeBuilder.extractKeywords");
        keywordsMonitor.start();
        
        String keywordsPrompt = LLMPromptTemplates.extractKeywords(summariesText);
        String entitiesPrompt = LLMPromptTemplates.extractEntities(summariesText);
        String examplesPrompt = LLMPromptTemplates.generateExampleQuestions(summariesText);
        
        LLMResponse keywordsResponse = llm.extractKeywords(keywordsPrompt);
        LLMResponse entitiesResponse = llm.extractEntities(entitiesPrompt);
        LLMResponse examplesResponse = llm.generateExampleQuestions(examplesPrompt);
        
        List<String> keywords = parseListResponse(keywordsResponse.content());
        List<String> entities = parseListResponse(entitiesResponse.content());
        List<String> examples = parseListResponse(examplesResponse.content());
        
        keywordsMonitor.stop();
        
        return new NodeMetadata(keywords, entities, examples, keywordsMonitor.getDurationMillis());
    }

    private record NodeMetadata(List<String> keywords, List<String> entities, List<String> examples, long extractionTime) {}

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
        
        TreeNode bestChild = null;
        int maxMatchCount = 0;
        
        for (String childId : currentNode.childrenIds()) {
            TreeNode child = tree.getNode(childId);
            if (child == null) {
                continue;
            }
            
            int matchCount = countKeywordMatches(docKeywords, child);
            
            if (matchCount > maxMatchCount) {
                maxMatchCount = matchCount;
                bestChild = child;
            }
        }
        
        if (maxMatchCount > 0) {
            return findBestNodeForDocument(bestChild, tree, result);
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
