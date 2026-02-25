package cn.nexon.zerovector.core.navigator;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.exception.NavigationException;
import cn.nexon.zerovector.core.exception.PromptLoadException;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
import cn.nexon.zerovector.core.util.PerformanceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class HybridNavigator {
    private static final Logger logger = LoggerFactory.getLogger(HybridNavigator.class);
    
    private final SemanticTree tree;
    private final KeywordDictionary dictionary;
    private final LLMProvider llm;
    private final MMapDocumentStore store;
    private final HookExecutor hookExecutor;
    
    public HybridNavigator(SemanticTree tree, KeywordDictionary dictionary, LLMProvider llm, MMapDocumentStore store) {
        this(tree, dictionary, llm, store, new DefaultHookExecutor());
    }

    public HybridNavigator(SemanticTree tree, KeywordDictionary dictionary, LLMProvider llm, MMapDocumentStore store, HookExecutor hookExecutor) {
        this.tree = tree;
        this.dictionary = dictionary;
        this.llm = llm;
        this.store = store;
        this.hookExecutor = hookExecutor != null ? hookExecutor : new DefaultHookExecutor();
    }
    
    public NavigationResult navigate(String query) {
        long startTime = System.currentTimeMillis();
        
        try {
            String fastTrackNodeId = null;
            
            try {
                String keywordsPrompt = LLMPromptTemplates.extractQueryKeywords(query);
                LLMResponse keywordsResponse = llm.extractQueryKeywords(keywordsPrompt);
                
                List<String> extractedKeywords = parseKeywordsResponse(keywordsResponse.content());
                
                if (!extractedKeywords.isEmpty()) {
                    Map<String, Double> candidates = dictionary.matchCandidatesFromKeywords(extractedKeywords);
                    if (!candidates.isEmpty()) {
                        fastTrackNodeId = Collections.max(candidates.entrySet(), Map.Entry.comparingByValue()).getKey();
                    }
                }
            } catch (Exception e) {
                Map<String, Double> candidates = dictionary.matchCandidates(query);
                if (!candidates.isEmpty()) {
                    fastTrackNodeId = Collections.max(candidates.entrySet(), Map.Entry.comparingByValue()).getKey();
                }
            }
            
            TreeNode currentNode;
            if (fastTrackNodeId != null) {
                currentNode = tree.getNode(fastTrackNodeId);
                if (currentNode == null) {
                    currentNode = tree.rootNode();
                }
            } else {
                currentNode = tree.rootNode();
            }
            
            if (currentNode == null) {
                throw new NavigationException(query, null, NavigationException.ERROR_CODE_TREE_NOT_INITIALIZED, 
                    "无法获取起始节点进行导航");
            }
                
            return navigateInternal(query, currentNode, new ArrayList<>(), startTime);
        } catch (NavigationException e) {
            throw e;
        } catch (Exception e) {
            throw new NavigationException(query, tree != null ? tree.rootNode().id() : null, 
                NavigationException.ERROR_CODE_LLM_DECISION_FAILED, "Navigation failed", e);
        }
    }
    
    private NavigationResult navigateInternal(String query, TreeNode startNode, List<NavigationPath> navigationHistory, long startTime) {
        List<TreeNode> path = new ArrayList<>();
        path.add(startNode);
        TreeNode current = startNode;
        int stepCount = 0;
        
        NavigationPath currentPath = new NavigationPath(
            query,
            List.of(startNode.id()),
            "Starting navigation from " + startNode.name()
        );
        navigationHistory.add(currentPath);
        
        while (current.type() != NodeType.LEAF) {
            if (!current.hasChildren()) {
                break;
            }
            
            stepCount++;
            
            String prompt = buildNavigationPrompt(query, current);
            
            LLMResponse response = llm.decideNavigation(prompt);
            
            NavigationAction action = parseNavigationResponse(response.content(), getCurrentChildNodes(current));
            
            switch (action) {
                case NavigationAction.SelectChild(String nodeId, String reasoning, double conf) -> {
                    if (conf < 0.3) {
                        return handleFallback(query, navigationHistory);
                    }
                    TreeNode nextNode = tree.getNode(nodeId);
                    if (nextNode != null) {
                        current = nextNode;
                        path.add(current);
                        
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
                }
            }
            
            hookExecutor.executeHooks(HookType.NAVIGATION_STEP,
                HookContext.builder(HookType.NAVIGATION_STEP)
                    .data("query", query)
                    .data("nodeName", current.name())
                    .data("stepCount", stepCount)
            );
        }
        
        List<DocumentChunk> chunks = loadChunks(current.chunkIds());
        
        long duration = System.currentTimeMillis() - startTime;
        hookExecutor.executeHooks(HookType.NAVIGATION_END,
            HookContext.builder(HookType.NAVIGATION_END)
                .data("query", query)
                .data("resultCount", chunks.size())
                .data("stepCount", stepCount)
                .durationMillis(duration)
        );
        
        return new NavigationResult(
            chunks,
            "Reached leaf node: " + current.name(),
            navigationHistory
        );
    }
    
    private List<TreeNode> getCurrentChildNodes(TreeNode current) {
        return current.childrenIds().stream()
            .map(tree::getNode)
            .collect(Collectors.toList());
    }
    
    private String buildNavigationPrompt(String query, TreeNode node) {
        List<String> childNodeDescriptions = node.childrenIds().stream()
            .map(tree::getNode)
            .filter(java.util.Objects::nonNull)
            .map(n -> n.name() + " - " + n.description())
            .toList();
        
        return LLMPromptTemplates.decideNavigation(
            query,
            node.name(),
            node.description(),
            childNodeDescriptions
        );
    }
    
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
    
    private NavigationResult handleFallback(String query, List<NavigationPath> navigationHistory) {
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
    
    private List<DocumentChunk> loadChunks(List<String> chunkIds) {
        return chunkIds.stream()
            .map(chunkId -> {
                DocumentChunk chunk = tree.getChunk(chunkId);
                if (chunk == null) {
                    String content = store.getChunk(chunkId);
                    if (content != null) {
                        return DocumentChunk.withContent(chunkId, content, null, Map.of());
                    }
                    return null;
                }
                
                if (chunk.isFilePathBased()) {
                    try {
                        String content = store.getChunkContent(chunk);
                        return new DocumentChunk(
                            chunk.id(),
                            content,
                            chunk.summary(),
                            chunk.filePath(),
                            chunk.md5(),
                            chunk.metadata()
                        );
                    } catch (Exception e) {
                        return chunk;
                    }
                }
                
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
            throw new PromptLoadException("decideNavigation", PromptLoadException.ERROR_CODE_PARSE_FAILED, 
                "Failed to parse navigation decision response", e);
        }
    }

    private List<String> parseKeywordsResponse(String response) {
        List<String> keywords = new ArrayList<>();
        String[] lines = response.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                keywords.add(trimmed.toLowerCase());
            }
        }
        
        return keywords;
    }
    
    private record NavigationDecisionResult(int selectedIndex, String reasoning, double confidence) {}
}
