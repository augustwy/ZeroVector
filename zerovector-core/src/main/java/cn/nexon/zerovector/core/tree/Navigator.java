package cn.nexon.zerovector.core.tree;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 语义导航器
 * 基于用户查询在语义树中进行导航，找到相关文档
 */
public class Navigator {
    private static final Logger logger = LoggerFactory.getLogger(Navigator.class);
    private static final int MAX_NAVIGATION_HISTORY = 100;
    
    private final SemanticTree semanticTree;
    private final LLMProvider llmService;
    private final MMapDocumentStore documentStore;
    
    private TreeNode currentNode;
    private final List<NavigationPath> navigationHistory;
    
    public Navigator(SemanticTree semanticTree, LLMProvider llmService, MMapDocumentStore documentStore) {
        this.semanticTree = semanticTree;
        this.llmService = llmService;
        this.documentStore = documentStore;
        this.currentNode = semanticTree.rootNode();
        this.navigationHistory = new CopyOnWriteArrayList<>();
    }
    
    private void addNavigationPath(NavigationPath path) {
        navigationHistory.add(path);
        if (navigationHistory.size() > MAX_NAVIGATION_HISTORY) {
            navigationHistory.remove(0);
        }
    }
    
    /**
     * 执行查询导航
     */
    public NavigationResult navigate(String query) {
        NavigationPath path = new NavigationPath(
            query,
            List.of(currentNode.id()),
            "Starting navigation from root"
        );
        addNavigationPath(path);
        
        return navigateRecursive(query, currentNode, 0);
    }
    
    /**
     * 递归导航逻辑
     */
    private NavigationResult navigateRecursive(String query, TreeNode node, int depth) {
        if (depth > 10) {
            return new NavigationResult(
                List.of(),
                "Navigation depth exceeded",
                navigationHistory
            );
        }
        
        if (node.isLeaf() || !node.hasChildren()) {
            List<DocumentChunk> chunks = loadChunksFromNode(node);
            return new NavigationResult(
                chunks,
                "Found relevant documents at leaf node",
                navigationHistory
            );
        }
        
        List<TreeNode> childNodes = node.childrenIds().stream()
            .map(semanticTree::getNode)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        List<String> childNodeDescriptions = childNodes.stream()
            .map(n -> n.name() + " - " + n.description())
            .toList();
        String navigationPrompt = LLMPromptTemplates.decideNavigation(
            query,
            node.name(),
            node.description(),
            childNodeDescriptions
        );
        
        LLMResponse response = llmService.decideNavigation(navigationPrompt);
        NavigationAction action = parseNavigationResponse(response.content(), childNodes);
        
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
                
                currentNode = nextNode;
                List<String> previousNodes = navigationHistory.get(navigationHistory.size() - 1).visitedNodes();
                List<String> newVisitedNodes = new ArrayList<>(previousNodes);
                newVisitedNodes.add(nodeId);
                addNavigationPath(new NavigationPath(
                    query,
                    newVisitedNodes,
                    reasoning
                ));
                
                yield navigateRecursive(query, nextNode, depth + 1);
            }
            
            case NavigationAction.SelectLeaves(List<String> chunkIds, String reasoning) -> {
                List<DocumentChunk> chunks = loadChunksByIds(chunkIds);
                yield new NavigationResult(
                    chunks,
                    reasoning,
                    navigationHistory
                );
            }
            
            case NavigationAction.ExpandMultiple(List<String> nodeIds, String reasoning) -> {
                List<DocumentChunk> allChunks = new ArrayList<>();
                for (String nodeId : nodeIds) {
                    TreeNode n = semanticTree.getNode(nodeId);
                    if (n != null && n.hasChunks()) {
                        allChunks.addAll(loadChunksFromNode(n));
                    }
                }
                
                yield new NavigationResult(
                    allChunks,
                    reasoning,
                    navigationHistory
                );
            }
            
            case NavigationAction.FallbackSearch(String reason) -> {
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
    
    private List<DocumentChunk> loadChunksFromNode(TreeNode node) {
        return node.chunkIds().stream()
            .map(chunkId -> loadChunkWithContent(chunkId))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    private List<DocumentChunk> loadChunksByIds(List<String> chunkIds) {
        return chunkIds.stream()
            .map(chunkId -> loadChunkWithContent(chunkId))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    private DocumentChunk loadChunkWithContent(String chunkId) {
        DocumentChunk chunk = semanticTree.getChunk(chunkId);
        if (chunk == null) {
            return null;
        }
        
        if (chunk.isFilePathBased()) {
            try {
                String content = documentStore.getChunkContent(chunk);
                return new DocumentChunk(
                    chunk.id(),
                    content,
                    chunk.summary(),
                    chunk.filePath(),
                    chunk.md5(),
                    chunk.metadata()
                );
            } catch (Exception e) {
                logger.warn("读取文件内容失败: {}", chunk.filePath(), e);
                return chunk;
            }
        }
        
        return chunk;
    }
    
    private NavigationAction parseNavigationResponse(String response, List<TreeNode> childNodes) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            NavigationDecisionResult result = objectMapper.readValue(response, NavigationDecisionResult.class);
            
            if (result.selectedIndex() >= 0 && result.selectedIndex() < childNodes.size()) {
                TreeNode selectedNode = childNodes.get(result.selectedIndex());
                return new NavigationAction.SelectChild(
                        selectedNode.id(),
                        result.reasoning(),
                        result.confidence()
                );
            } else {
                return new NavigationAction.Stop(result.reasoning());
            }
        } catch (Exception e) {
            logger.error("解析导航决策失败: {}", e.getMessage());
            if (childNodes.isEmpty()) {
                return new NavigationAction.Stop("没有可用的子节点");
            } else {
                TreeNode selectedNode = childNodes.get(0);
                return new NavigationAction.SelectChild(
                        selectedNode.id(),
                        "默认选择第一个子节点",
                        0.5
                );
            }
        }
    }
    
    private record NavigationDecisionResult(int selectedIndex, String reasoning, double confidence) {}
    
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