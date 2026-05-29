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

package cn.nexon.zerovector.core.navigator;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.exception.NavigationException;
import cn.nexon.zerovector.core.exception.PromptLoadException;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 混合导航器
 * 结合关键词匹配和 LLM 决策进行语义树导航
 */
public class HybridNavigator {
    private static final Logger logger = LoggerFactory.getLogger(HybridNavigator.class);
    
    private final SemanticTree tree;
    private final KeywordDictionary dictionary;
    private final LLMProvider llm;
    private final ChunkStorage chunkStore;
    private final HookExecutor hookExecutor;
    private final int maxNavigationSteps;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public HybridNavigator(SemanticTree tree, KeywordDictionary dictionary, LLMProvider llm, ChunkStorage chunkStore, int maxNavigationSteps) {
        this(tree, dictionary, llm, chunkStore, maxNavigationSteps, new DefaultHookExecutor());
    }

    public HybridNavigator(SemanticTree tree, KeywordDictionary dictionary, LLMProvider llm, ChunkStorage chunkStore, int maxNavigationSteps, HookExecutor hookExecutor) {
        this.tree = tree;
        this.dictionary = dictionary;
        this.llm = llm;
        this.chunkStore = chunkStore;
        this.maxNavigationSteps = maxNavigationSteps;
        this.hookExecutor = Objects.requireNonNullElse(hookExecutor, new DefaultHookExecutor());
    }
    
    /**
     * 执行导航
     * 根据查询在语义树中导航，返回相关文档
     *
     * @param query 查询字符串
     * @return 导航结果，包含 LLM 调用统计数据
     */
    public NavigationResult navigate(String query) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats stats = new LLMUsageStats();
        
        try {
            String fastTrackNodeId = null;
            
            try {
                String keywordsPrompt = LLMPromptTemplates.extractQueryKeywords(query);
                LLMResponse keywordsResponse = llm.chat(keywordsPrompt, SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS);
                stats.add(keywordsResponse);

                List<String> extractedKeywords = parseKeywordsResponse(keywordsResponse.content());

                if (!extractedKeywords.isEmpty()) {
                    Map<String, Double> candidates = dictionary.matchCandidatesFromKeywords(extractedKeywords);
                    if (!candidates.isEmpty()) {
                        fastTrackNodeId = Collections.max(candidates.entrySet(), Map.Entry.comparingByValue()).getKey();
                    }
                }
            } catch (PromptLoadException e) {
                // LLM调用失败，降级到字典匹配
            }

            if (fastTrackNodeId == null) {
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
                
            return navigateInternal(query, currentNode, new ArrayList<>(), startTime, stats);
        } catch (NavigationException e) {
            throw e;
        } catch (Exception e) {
            throw new NavigationException(query, tree != null ? tree.rootNode().id() : null, 
                NavigationException.ERROR_CODE_LLM_DECISION_FAILED, "Navigation failed", e);
        }
    }
    
    private NavigationResult navigateInternal(String query, TreeNode startNode, List<NavigationPath> navigationHistory, long startTime, LLMUsageStats stats) {
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

            if (stepCount > maxNavigationSteps) {
                logger.warn("导航超过最大步数限制 ({}), 强制停止", maxNavigationSteps);
                return handleFallback(query, navigationHistory, stats);
            }

            String prompt = buildNavigationPrompt(query, current);
            
            LLMResponse response = llm.chat(prompt, SmartCacheStrategy.RequestType.DECIDE_NAVIGATION);
            stats.add(response);
            
            NavigationAction action = parseNavigationResponse(response.content(), getCurrentChildNodes(current));
            
            switch (action) {
                case NavigationAction.SelectChild(String nodeId, String reasoning, double conf) -> {
                    if (conf < 0.3) {
                        return handleFallback(query, navigationHistory, stats);
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
                    return handleMultiPath(query, nodeIds, navigationHistory, stats);
                }
                case NavigationAction.FallbackSearch(String reason) -> {
                    return handleFallback(query, navigationHistory, stats);
                }
                case NavigationAction.Stop(String reasoning) -> {
                    return new NavigationResult(
                        List.of(),
                        reasoning,
                        navigationHistory,
                        stats
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
                .data("llmUsageStats", stats)
                .durationMillis(duration)
        );
        
        return new NavigationResult(
            chunks,
            "Reached leaf node: " + current.name(),
            navigationHistory,
            stats
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
    
    private NavigationResult handleMultiPath(String query, List<String> nodeIds, List<NavigationPath> navigationHistory, LLMUsageStats stats) {
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
            navigationHistory,
            stats
        );
    }
    
    private NavigationResult handleFallback(String query, List<NavigationPath> navigationHistory, LLMUsageStats stats) {
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
                navigationHistory,
                stats
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
                    navigationHistory,
                    stats
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
            navigationHistory,
            stats
        );
    }
    
    private List<DocumentChunk> loadChunks(List<String> chunkIds) {
        return chunkIds.parallelStream()
            .map(this::resolveChunk)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
    }

    private DocumentChunk resolveChunk(String chunkId) {
        DocumentChunk chunk = tree.getChunk(chunkId);
        if (chunk == null) {
            String content = chunkStore.getChunkContent(chunkId);
            return content != null ? DocumentChunk.withContent(chunkId, content, null, Map.of()) : null;
        }

        if (chunk.isFilePathBased()) {
            try {
                String content = chunkStore.getChunkContent(chunk.id());
                return new DocumentChunk(chunk.id(), content, chunk.summary(),
                    chunk.filePath(), chunk.md5(), chunk.metadata());
            } catch (Exception e) {
                return chunk;
            }
        }

        if (chunk.content() == null || chunk.content().isEmpty()) {
            String content = chunkStore.getChunkContent(chunkId);
            if (content != null) {
                return new DocumentChunk(chunk.id(), content, chunk.summary(),
                    chunk.filePath(), chunk.md5(), chunk.metadata());
            }
        }

        return chunk;
    }
    
    private NavigationAction parseNavigationResponse(String response, List<TreeNode> childNodes) {
        try {
            NavigationDecisionResult result = OBJECT_MAPPER.readValue(response, NavigationDecisionResult.class);
            
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
    
}
