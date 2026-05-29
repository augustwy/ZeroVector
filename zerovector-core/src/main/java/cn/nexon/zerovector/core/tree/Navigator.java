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
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.util.JsonUtils;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 语义树导航器
 * 负责在语义树中进行导航搜索
 */
public class Navigator {
    private static final Logger logger = LoggerFactory.getLogger(Navigator.class);
    private static final int MAX_NAVIGATION_HISTORY = 100;
    
    private final SemanticTree semanticTree;
    private final LLMProvider llmService;
    private final ChunkStorage chunkStore;
    private final HookExecutor hookExecutor;
    private final int maxNavigationSteps;

    private TreeNode currentNode;
    private final List<NavigationPath> navigationHistory;

    public Navigator(SemanticTree semanticTree, LLMProvider llmService, ChunkStorage chunkStore, int maxNavigationSteps) {
        this(semanticTree, llmService, chunkStore, maxNavigationSteps, new DefaultHookExecutor());
    }

    public Navigator(SemanticTree semanticTree, LLMProvider llmService, ChunkStorage chunkStore, int maxNavigationSteps, HookExecutor hookExecutor) {
        this.semanticTree = semanticTree;
        this.llmService = llmService;
        this.chunkStore = chunkStore;
        this.maxNavigationSteps = maxNavigationSteps;
        this.hookExecutor = Objects.requireNonNullElse(hookExecutor, new DefaultHookExecutor());
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
     *
     * @param query 查询字符串
     * @return 导航结果，包含 LLM 调用统计数据
     */
    public NavigationResult navigate(String query) {
        NavigationPath path = new NavigationPath(
            query,
            List.of(currentNode.id()),
            "Starting navigation from root"
        );
        addNavigationPath(path);
        
        return navigateRecursive(query, currentNode, 0, new LLMUsageStats());
    }
    
    /**
     * 递归导航逻辑
     */
    private NavigationResult navigateRecursive(String query, TreeNode node, int depth, LLMUsageStats stats) {
        if (depth > maxNavigationSteps) {
            return new NavigationResult(
                List.of(),
                "Navigation depth exceeded",
                navigationHistory,
                stats
            );
        }
        
        if (node.isLeaf() || !node.hasChildren()) {
            List<DocumentChunk> chunks = loadChunksFromNode(node);
            return new NavigationResult(
                chunks,
                "Found relevant documents at leaf node",
                navigationHistory,
                stats
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
        
        LLMResponse response = llmService.chat(navigationPrompt, SmartCacheStrategy.RequestType.DECIDE_NAVIGATION);
        stats.add(response);
        
        NavigationAction action = parseNavigationResponse(response.content(), childNodes);
        
        return switch (action) {
            case NavigationAction.SelectChild(String nodeId, String reasoning, double confidence) -> {
                TreeNode nextNode = semanticTree.getNode(nodeId);
                if (nextNode == null) {
                    yield new NavigationResult(
                        List.of(),
                        "Invalid node selected: " + nodeId,
                        navigationHistory,
                        stats
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
                
                yield navigateRecursive(query, nextNode, depth + 1, stats);
            }
            
            case NavigationAction.SelectLeaves(List<String> chunkIds, String reasoning) -> {
                List<DocumentChunk> chunks = loadChunksByIds(chunkIds);
                yield new NavigationResult(
                    chunks,
                    reasoning,
                    navigationHistory,
                    stats
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
                    navigationHistory,
                    stats
                );
            }
            
            case NavigationAction.FallbackSearch(String reason) -> {
                yield new NavigationResult(
                    List.of(),
                    reason,
                    navigationHistory,
                    stats
                );
            }
            
            case NavigationAction.Stop(String reasoning) -> new NavigationResult(
                List.of(),
                reasoning,
                navigationHistory,
                stats
            );
        };
    }
    
    private List<DocumentChunk> loadChunksFromNode(TreeNode node) {
        return node.chunkIds().parallelStream()
            .map(chunkId -> loadChunkWithContent(chunkId))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    private List<DocumentChunk> loadChunksByIds(List<String> chunkIds) {
        return chunkIds.parallelStream()
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
                String content = chunkStore.getChunkContent(chunk.id());
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
            NavigationDecisionResult result = JsonUtils.getObjectMapper().readValue(response, NavigationDecisionResult.class);
            
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
