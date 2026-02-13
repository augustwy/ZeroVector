package cn.nexon.zerovector.core.tree;

import cn.nexon.zerovector.core.ai.LLMService;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 语义导航器
 * 基于用户查询在语义树中进行导航，找到相关文档
 */
public class Navigator {
    private final SemanticTree semanticTree;
    private final LLMService llmService;
    private final MMapDocumentStore documentStore;
    
    // 当前导航状态
    private TreeNode currentNode;
    private List<NavigationPath> navigationHistory;
    
    public Navigator(SemanticTree semanticTree, LLMService llmService, MMapDocumentStore documentStore) {
        this.semanticTree = semanticTree;
        this.llmService = llmService;
        this.documentStore = documentStore;
        this.currentNode = semanticTree.rootNode();
        this.navigationHistory = new ArrayList<>();
    }
    
    /**
     * 执行查询导航
     */
    public NavigationResult navigate(String query) {
        // 记录导航开始
        NavigationPath path = new NavigationPath(
            query,
            List.of(currentNode.id()),
            "Starting navigation from root"
        );
        navigationHistory.add(path);
        
        // 执行导航逻辑
        return navigateRecursive(query, currentNode, 0);
    }
    
    /**
     * 递归导航逻辑
     */
    private NavigationResult navigateRecursive(String query, TreeNode node, int depth) {
        // 防止无限递归
        if (depth > 10) {
            return new NavigationResult(
                List.of(),
                "Navigation depth exceeded",
                navigationHistory
            );
        }
        
        // 如果是叶子节点，直接返回相关文档
        if (node.isLeaf() || !node.hasChildren()) {
            List<DocumentChunk> chunks = node.chunkIds().stream()
                .map(chunkId -> {
                    DocumentChunk chunk = semanticTree.getChunk(chunkId);
                    if (chunk == null) {
                        return null;
                    }
                    
                    // 如果是基于文件路径的文档块，使用MMapDocumentStore读取文件内容
                    if (chunk.isFilePathBased()) {
                        try {
                            String content = documentStore.getChunkContent(chunk);
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
                    
                    return chunk;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            return new NavigationResult(
                chunks,
                "Found relevant documents at leaf node",
                navigationHistory
            );
        }
        
        // 获取子节点
        List<TreeNode> childNodes = node.childrenIds().stream()
            .map(semanticTree::getNode)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        // 使用LLM决定下一步导航动作
        NavigationAction action = llmService.decideNavigation(query, node, childNodes);
        
        // 根据动作执行导航
        return switch (action) {
            case NavigationAction.SelectChild(String nodeId, String reasoning, double confidence) -> {
                TreeNode nextNode = semanticTree.getNode(nodeId);
                if (nextNode == null) {
                    yield new NavigationResult(
                        List.of(),
                        "Invalid node selected: " + nodeId,
                        navigationHistory
                    );
                }
                
                // 更新当前节点和导航历史
                currentNode = nextNode;
                List<String> previousNodes = navigationHistory.get(navigationHistory.size() - 1).visitedNodes();
                List<String> newVisitedNodes = new ArrayList<>(previousNodes);
                newVisitedNodes.add(nodeId);
                navigationHistory.add(new NavigationPath(
                    query,
                    newVisitedNodes,
                    reasoning
                ));
                
                // 递归导航
                yield navigateRecursive(query, nextNode, depth + 1);
            }
            
            case NavigationAction.SelectLeaves(List<String> chunkIds, String reasoning) -> {
                List<DocumentChunk> chunks = chunkIds.stream()
                    .map(chunkId -> {
                        DocumentChunk chunk = semanticTree.getChunk(chunkId);
                        if (chunk == null) {
                            return null;
                        }
                        
                        // 如果是基于文件路径的文档块，使用MMapDocumentStore读取文件内容
                        if (chunk.isFilePathBased()) {
                            try {
                                String content = documentStore.getChunkContent(chunk);
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
                        
                        return chunk;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                
                yield new NavigationResult(
                    chunks,
                    reasoning,
                    navigationHistory
                );
            }
            
            case NavigationAction.ExpandMultiple(List<String> nodeIds, String reasoning) -> {
                // 处理多分支并行处理
                List<DocumentChunk> allChunks = new ArrayList<>();
                for (String nodeId : nodeIds) {
                    TreeNode n = semanticTree.getNode(nodeId);
                    if (n != null && n.hasChunks()) {
                        List<DocumentChunk> nodeChunks = n.chunkIds().stream()
                            .map(chunkId -> {
                                DocumentChunk chunk = semanticTree.getChunk(chunkId);
                                if (chunk == null) {
                                    return null;
                                }
                                
                                // 如果是基于文件路径的文档块，使用MMapDocumentStore读取文件内容
                                if (chunk.isFilePathBased()) {
                                    try {
                                        String content = documentStore.getChunkContent(chunk);
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
                                
                                return chunk;
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                        allChunks.addAll(nodeChunks);
                    }
                }
                
                yield new NavigationResult(
                    allChunks,
                    reasoning,
                    navigationHistory
                );
            }
            
            case NavigationAction.FallbackSearch(String reason) -> {
                // 处理回退搜索
                yield new NavigationResult(
                    List.of(),
                    reason,
                    navigationHistory
                );
            }
            
            case NavigationAction.Stop(String reasoning) -> new NavigationResult(
                List.of(),
                reasoning,
                navigationHistory
            );
        };
    }
    
    /**
     * 获取当前节点
     */
    public TreeNode getCurrentNode() {
        return currentNode;
    }
    
    /**
     * 重置导航状态到根节点
     */
    public void reset() {
        currentNode = semanticTree.rootNode();
        navigationHistory.clear();
    }
}