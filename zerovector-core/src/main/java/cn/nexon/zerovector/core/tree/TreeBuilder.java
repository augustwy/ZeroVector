package cn.nexon.zerovector.core.tree;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
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
    private final ConcurrencyProperties concurrencyConfig;

    public TreeBuilder(LLMProvider llm, KeywordDictionary dictionary, MMapDocumentStore store, ConcurrencyProperties concurrencyConfig) {
        this.llm = llm;
        this.dictionary = dictionary;
        this.store = store;
        this.concurrencyConfig = concurrencyConfig;
    }

    public SemanticTree build(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunks) {
        logger.info("开始构建语义树，共 {} 个文档", comprehendResultMap.size());
        
        TreeBuildResult result = buildRecursiveWithNodes("Root", comprehendResultMap);
        TreeNode root = result.root();
        
        logger.info("语义树构建完成，包含 {} 个文档，{} 个节点", comprehendResultMap.size(), result.nodes().size());
        return new SemanticTree(root, result.nodes(), chunks);
    }

    public SemanticTree updateTree(SemanticTree existingTree, List<DocumentComprehendResult> newResults, Map<String, DocumentChunk> newChunks) {
        logger.info("开始增量更新语义树，共 {} 个新文档", newResults.size());
        
        if (existingTree == null || existingTree.rootNode() == null) {
            logger.info("现有语义树为空，执行完整构建");
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
                logger.warn("文档 {} 的 chunk 信息不存在，跳过", chunkId);
                continue;
            }
            
            TreeNode targetNode = findBestNodeForDocument(existingTree.rootNode(), existingTree, result);
            
            if (targetNode != null) {
                if (targetNode.isLeaf()) {
                    List<String> newChunkIds = new ArrayList<>(targetNode.chunkIds());
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
                    logger.debug("文档 {} 已添加到叶子节点 {}", chunkId, targetNode.name());
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
                    logger.debug("文档 {} 已添加到分类节点 {}，创建新叶子节点", chunkId, targetNode.name());
                }
            } else {
                TreeNode newLeafNode = createLeafNodeForDocument(chunkId, result);
                updatedNodes.put(newLeafNode.id(), newLeafNode);
                
                TreeNode root = existingTree.rootNode();
                List<String> newChildrenIds = new ArrayList<>(root.childrenIds());
                newChildrenIds.add(newLeafNode.id());
                
                TreeNode updatedRoot = new TreeNode(
                    root.id(),
                    root.name(),
                    root.description(),
                    NodeType.CATEGORY,
                    newChildrenIds,
                    root.chunkIds(),
                    root.keyEntities(),
                    root.keywords(),
                    root.exampleQuestions()
                );
                
                updatedNodes.put(root.id(), updatedRoot);
                currentRoot = updatedRoot;
                logger.debug("文档 {} 已添加到根节点", chunkId);
            }
        }
        
        logger.info("增量更新完成，语义树包含 {} 个文档，{} 个节点", updatedChunks.size(), updatedNodes.size());
        return new SemanticTree(currentRoot, updatedNodes, updatedChunks);
    }

    private TreeBuildResult buildRecursiveWithNodes(String name, Map<String, DocumentComprehendResult> documents) {
        Map<String, TreeNode> allNodes = new ConcurrentHashMap<>();
        
        if (documents.size() <= CLUSTERING_THRESHOLD) {
            try {
                List<String> summaries = documents.values().stream()
                    .map(DocumentComprehendResult::summary)
                    .toList();
                String clusterPrompt = LLMPromptTemplates.clusterDocumentChunks(summaries);
                String response = llm.clusterDocuments(clusterPrompt);
                List<NodeCategory> categories = parseClusterResponse(response, documents);
                
                if (categories.size() == 1 && categories.get(0).getDocuments().size() == documents.size()) {
                    TreeNode leafNode = createLeafNode(name, categories.get(0).getDocuments());
                    allNodes.put(leafNode.id(), leafNode);
                    return new TreeBuildResult(leafNode, allNodes);
                }
                
                if (categories.size() > 1) {
                    List<String> childIds = new ArrayList<>();
                    
                    for (NodeCategory cat : categories) {
                        String summariesText = cat.getDocuments().values().stream()
                            .map(DocumentComprehendResult::summary)
                            .collect(Collectors.joining(" "));
                        
                        String keywordsPrompt = LLMPromptTemplates.extractKeywords(summariesText);
                        String entitiesPrompt = LLMPromptTemplates.extractEntities(summariesText);
                        String examplesPrompt = LLMPromptTemplates.generateExampleQuestions(summariesText);
                        
                        List<String> keywords = parseListResponse(llm.extractKeywords(keywordsPrompt));
                        List<String> entities = parseListResponse(llm.extractEntities(entitiesPrompt));
                        List<String> examples = parseListResponse(llm.generateExampleQuestions(examplesPrompt));
                        
                        dictionary.addEntries(keywords, cat.getNodeId());
                        
                        TreeBuildResult childResult = buildRecursiveWithNodes(cat.getName(), cat.getDocuments());
                        TreeNode childNode = childResult.root();
                        childIds.add(childNode.id());
                        
                        allNodes.putAll(childResult.nodes());
                    }
                    
                    String nodeId = UUID.randomUUID().toString();
                    String allSummaries = documents.values().stream()
                        .map(DocumentComprehendResult::summary)
                        .collect(Collectors.joining(" "));
                    
                    String nodeKeywordsPrompt = LLMPromptTemplates.extractKeywords(allSummaries);
                    String nodeEntitiesPrompt = LLMPromptTemplates.extractEntities(allSummaries);
                    String nodeExamplesPrompt = LLMPromptTemplates.generateExampleQuestions(allSummaries);
                    
                    List<String> nodeKeywords = parseListResponse(llm.extractKeywords(nodeKeywordsPrompt));
                    List<String> nodeEntities = parseListResponse(llm.extractEntities(nodeEntitiesPrompt));
                    List<String> nodeExamples = parseListResponse(llm.generateExampleQuestions(nodeExamplesPrompt));
                    
                    dictionary.addEntries(nodeKeywords, nodeId);
                    
                    TreeNode currentNode = new TreeNode(
                        nodeId,
                        name,
                        "Category node containing " + categories.size() + " subcategories",
                        NodeType.CATEGORY,
                        childIds,
                        List.of(),
                        nodeEntities,
                        nodeKeywords,
                        nodeExamples
                    );
                    
                    allNodes.put(nodeId, currentNode);
                    return new TreeBuildResult(currentNode, allNodes);
                }
            } catch (Exception e) {
                logger.warn("聚类失败，创建叶子节点: {}", e.getMessage());
            }
            
            TreeNode leafNode = createLeafNode(name, documents);
            allNodes.put(leafNode.id(), leafNode);
            return new TreeBuildResult(leafNode, allNodes);
        }
        
        List<String> summaries = documents.values().stream()
            .map(DocumentComprehendResult::summary)
            .toList();
        String clusterPrompt = LLMPromptTemplates.clusterDocumentChunks(summaries);
        String response = llm.clusterDocuments(clusterPrompt);
        List<NodeCategory> categories = parseClusterResponse(response, documents);
        
        List<String> childIds = new ArrayList<>();
        
        for (NodeCategory cat : categories) {
            String summariesText = cat.getDocuments().values().stream()
                .map(DocumentComprehendResult::summary)
                .collect(Collectors.joining(" "));
            
            String keywordsPrompt = LLMPromptTemplates.extractKeywords(summariesText);
            String entitiesPrompt = LLMPromptTemplates.extractEntities(summariesText);
            String examplesPrompt = LLMPromptTemplates.generateExampleQuestions(summariesText);
            
            List<String> keywords = parseListResponse(llm.extractKeywords(keywordsPrompt));
            List<String> entities = parseListResponse(llm.extractEntities(entitiesPrompt));
            List<String> examples = parseListResponse(llm.generateExampleQuestions(examplesPrompt));
            
            dictionary.addEntries(keywords, cat.getNodeId());
            
            TreeBuildResult childResult = buildRecursiveWithNodes(cat.getName(), cat.getDocuments());
            TreeNode childNode = childResult.root();
            childIds.add(childNode.id());
            
            allNodes.putAll(childResult.nodes());
        }
        
        String nodeId = UUID.randomUUID().toString();
        String allSummaries = documents.values().stream()
            .map(DocumentComprehendResult::summary)
            .collect(Collectors.joining(" "));
        
        String nodeKeywordsPrompt = LLMPromptTemplates.extractKeywords(allSummaries);
        String nodeEntitiesPrompt = LLMPromptTemplates.extractEntities(allSummaries);
        String nodeExamplesPrompt = LLMPromptTemplates.generateExampleQuestions(allSummaries);
        
        List<String> nodeKeywords = parseListResponse(llm.extractKeywords(nodeKeywordsPrompt));
        List<String> nodeEntities = parseListResponse(llm.extractEntities(nodeEntitiesPrompt));
        List<String> nodeExamples = parseListResponse(llm.generateExampleQuestions(nodeExamplesPrompt));
        
        dictionary.addEntries(nodeKeywords, nodeId);
        
        TreeNode currentNode = new TreeNode(
            nodeId,
            name,
            "Category node containing " + categories.size() + " subcategories",
            NodeType.CATEGORY,
            childIds,
            List.of(),
            nodeEntities,
            nodeKeywords,
            nodeExamples
        );
        
        allNodes.put(nodeId, currentNode);
        return new TreeBuildResult(currentNode, allNodes);
    }

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
            result.summary().substring(0, Math.min(50, result.summary().length())),
            "Document: " + docId,
            NodeType.LEAF,
            List.of(),
            List.of(docId),
            result.entities(),
            keywords,
            result.exampleQuestions()
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
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            ClusterResponse result = objectMapper.readValue(response, ClusterResponse.class);
            
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
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            StringListResponse result = objectMapper.readValue(response, StringListResponse.class);
            return result.items();
        } catch (Exception e) {
            logger.error("解析列表响应失败: {}", e.getMessage());
            return List.of();
        }
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
    private record StringListResponse(List<String> items) {}
    
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
