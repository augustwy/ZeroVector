package cn.nexon.zerovector.core.navigator;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 混合导航器
 * 目标：结合关键词跳转与 LLM 语义推理，实现精准导航
 */
public class HybridNavigator {
    
    private final SemanticTree tree;
    private final KeywordDictionary dictionary;
    private final LLMProvider llm;
    private final MMapDocumentStore store;
    
    public HybridNavigator(SemanticTree tree, KeywordDictionary dictionary, LLMProvider llm, MMapDocumentStore store) {
        this.tree = tree;
        this.dictionary = dictionary;
        this.llm = llm;
        this.store = store;
    }
    
    /**
     * 执行导航
     */
    public NavigationResult navigate(String query) {
        // [Phase 1] 关键词匹配 (快速通道)
        Map<String, Double> candidates = dictionary.matchCandidates(query);
        String fastTrackNodeId = null;
        
        // 如果存在高权重命中，走快速通道
        if (!candidates.isEmpty()) {
            fastTrackNodeId = Collections.max(candidates.entrySet(), Map.Entry.comparingByValue()).getKey();
        }
        
        // [Phase 2] LLM 语义决策
        TreeNode currentNode = (fastTrackNodeId != null) 
            ? tree.getNode(fastTrackNodeId) 
            : tree.rootNode();
            
        return navigateInternal(query, currentNode, new ArrayList<>());
    }
    
    /**
     * 内部导航逻辑
     */
    private NavigationResult navigateInternal(String query, TreeNode startNode, List<NavigationPath> navigationHistory) {
        List<TreeNode> path = new ArrayList<>();
        path.add(startNode);
        TreeNode current = startNode;
        
        // 记录导航开始
        NavigationPath currentPath = new NavigationPath(
            query,
            List.of(startNode.id()),
            "Starting navigation from " + startNode.name()
        );
        navigationHistory.add(currentPath);
        
        while (current.type() != NodeType.LEAF && current.hasChildren()) {
            // 构建带"提示"的 Prompt
            String prompt = buildNavigationPrompt(query, current);
            
            // 调用 LLM
            NavigationAction action = llm.decideNavigation(prompt, current, getCurrentChildNodes(current));
            
            // [KEY] 模式匹配处理决策
            switch (action) {
                case NavigationAction.SelectChild(String nodeId, String reasoning, double conf) -> {
                    // 如果置信度过低，触发 Fallback
                    if (conf < 0.3) {
                        return handleFallback(query, navigationHistory);
                    }
                    TreeNode nextNode = tree.getNode(nodeId);
                    if (nextNode != null) {
                        current = nextNode;
                        path.add(current);
                        
                        // 记录导航路径
                        List<String> previousNodes = navigationHistory.get(navigationHistory.size() - 1).visitedNodes();
                        List<String> newVisitedNodes = new ArrayList<>(previousNodes);
                        newVisitedNodes.add(nodeId);
                        navigationHistory.add(new NavigationPath(
                            query,
                            newVisitedNodes,
                            reasoning
                        ));
                    }
                }
                case NavigationAction.ExpandMultiple(List<String> nodeIds, String reasoning) -> {
                    // [Phase 1 功能] 多分支并行处理
                    return handleMultiPath(query, nodeIds, navigationHistory);
                }
                case NavigationAction.FallbackSearch(String reason) -> {
                    return handleFallback(query, navigationHistory);
                }
                case NavigationAction.Stop(String reasoning) -> {
                    return new NavigationResult(
                        List.of(),
                        reasoning,
                        navigationHistory
                    );
                }
                default -> {
                    // 处理其他情况
                }
            }
        }
        
        // [Phase 3] 到达叶子节点，读取文档
        List<DocumentChunk> chunks = loadChunks(current.chunkIds());
        return new NavigationResult(
            chunks,
            "Reached leaf node: " + current.name(),
            navigationHistory
        );
    }
    
    /**
     * 获取当前节点的子节点列表
     */
    private List<TreeNode> getCurrentChildNodes(TreeNode current) {
        return current.childrenIds().stream()
            .map(tree::getNode)
            .collect(Collectors.toList());
    }
    
    /**
     * [CRITICAL] 构建 Prompt 时注入关键词信息
     */
    private String buildNavigationPrompt(String query, TreeNode node) {
        // 获取当前节点的关键词，显式告诉 LLM
        String keywordHints = String.join(", ", node.keywords());
        String entityHints = String.join(", ", node.keyEntities());
        
        return String.format("""
            用户问题: %s
            当前节点: %s
            节点描述: %s
            该节点核心实体: %s
            该节点关键词: %s
            
            请判断应该进入哪个子节点？
            """, query, node.name(), node.description(), entityHints, keywordHints);
    }
    
    /**
     * 处理多路径情况
     */
    private NavigationResult handleMultiPath(String query, List<String> nodeIds, List<NavigationPath> navigationHistory) {
        List<DocumentChunk> allChunks = new ArrayList<>();
        List<String> allNodeIds = new ArrayList<>();
        
        for (String nodeId : nodeIds) {
            TreeNode node = tree.getNode(nodeId);
            if (node != null) {
                allNodeIds.add(nodeId);
                if (node.isLeaf() && node.hasChunks()) {
                    allChunks.addAll(loadChunks(node.chunkIds()));
                }
            }
        }
        
        // 记录多路径导航
        navigationHistory.add(new NavigationPath(
            query,
            allNodeIds,
            "Multi-path expansion"
        ));
        
        return new NavigationResult(
            allChunks,
            "Multi-path expansion across " + nodeIds.size() + " nodes",
            navigationHistory
        );
    }
    
    /**
     * 处理回退搜索
     */
    private NavigationResult handleFallback(String query, List<NavigationPath> navigationHistory) {
        // 使用关键词词典进行全文搜索
        Map<String, Double> candidates = dictionary.matchCandidates(query);
        
        if (candidates.isEmpty()) {
            navigationHistory.add(new NavigationPath(
                query,
                List.of(),
                "No results found in fallback search"
            ));
            return new NavigationResult(
                List.of(),
                "No results found",
                navigationHistory
            );
        }
        
        // 获取最相关的节点
        String topNodeId = candidates.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
            
        if (topNodeId != null) {
            TreeNode node = tree.getNode(topNodeId);
            if (node != null && node.hasChunks()) {
                navigationHistory.add(new NavigationPath(
                    query,
                    List.of(topNodeId),
                    "Fallback search result"
                ));
                return new NavigationResult(
                    loadChunks(node.chunkIds()),
                    "Fallback search result: " + node.name(),
                    navigationHistory
                );
            }
        }
        
        navigationHistory.add(new NavigationPath(
            query,
            List.of(),
            "Fallback search failed"
        ));
        return new NavigationResult(
            List.of(),
            "Fallback search failed",
            navigationHistory
        );
    }
    
    /**
     * 加载文档块
     */
    private List<DocumentChunk> loadChunks(List<String> chunkIds) {
        return chunkIds.stream()
            .map(chunkId -> {
                // 首先从语义树中获取DocumentChunk对象
                DocumentChunk chunk = tree.getChunk(chunkId);
                if (chunk == null) {
                    // 如果语义树中没有，尝试从存储中获取内容并创建DocumentChunk
                    String content = store.getChunk(chunkId);
                    if (content != null) {
                        return DocumentChunk.withContent(chunkId, content, null, Map.of());
                    }
                    return null;
                }
                
                // 如果是基于文件路径的文档块，使用MMapDocumentStore读取文件内容
                if (chunk.isFilePathBased()) {
                    try {
                        String content = store.getChunkContent(chunk);
                        // 创建一个新的DocumentChunk，包含从文件读取的内容
                        return new DocumentChunk(
                            chunk.id(),
                            content,  // 从文件读取的实际内容
                            chunk.summary(),
                            chunk.filePath(),
                            chunk.md5(),
                            chunk.metadata()
                        );
                    } catch (Exception e) {
                        // 如果读取失败，返回原始块
                        return chunk;
                    }
                }
                
                // 如果是基于内容的文档块，但内容为空，尝试从存储中加载
                if (chunk.content() == null || chunk.content().isEmpty()) {
                    String content = store.getChunk(chunkId);
                    if (content != null) {
                        return new DocumentChunk(
                            chunk.id(),
                            content,
                            chunk.summary(),
                            chunk.filePath(),
                            chunk.md5(),
                            chunk.metadata()
                        );
                    }
                }
                
                return chunk;
            })
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
    }
}