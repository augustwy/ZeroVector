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
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.exception.NavigationException;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationAction;
import cn.nexon.zerovector.core.model.NavigationDecisionResult;
import cn.nexon.zerovector.core.model.NavigationPath;
import cn.nexon.zerovector.core.model.NavigationResult;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.model.TreeNode;
import cn.nexon.zerovector.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 混合导航器：关键词倒排快速定位 + LLM 语义决策。
 *
 * <p>导航状态（当前节点、访问路径）全部是 {@link #navigate(String)} 的局部变量，
 * 实例本身无状态，可安全复用。
 */
public final class HybridNavigator {

    private static final Logger logger = LoggerFactory.getLogger(HybridNavigator.class);
    private static final double MIN_CONFIDENCE = 0.3;

    private final SemanticTree tree;
    private final KeywordDictionary dictionary;
    private final LLMProvider llm;
    private final HookExecutor hookExecutor;
    private final int maxNavigationSteps;

    public HybridNavigator(SemanticTree tree, KeywordDictionary dictionary, LLMProvider llm,
                           int maxNavigationSteps) {
        this(tree, dictionary, llm, maxNavigationSteps, new DefaultHookExecutor());
    }

    public HybridNavigator(SemanticTree tree, KeywordDictionary dictionary, LLMProvider llm,
                           int maxNavigationSteps, HookExecutor hookExecutor) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.maxNavigationSteps = Math.max(1, maxNavigationSteps);
        this.hookExecutor = Objects.requireNonNullElseGet(hookExecutor, DefaultHookExecutor::new);
    }

    /**
     * 执行导航。
     *
     * @param query 查询字符串
     * @return 导航结果（含 LLM 调用统计）
     */
    public NavigationResult navigate(String query) {
        if (query == null || query.isBlank()) {
            throw new NavigationException(query, null, NavigationException.ERROR_CODE_INVALID_QUERY,
                    "Query must not be empty");
        }

        LLMUsageStats stats = new LLMUsageStats();
        try {
            TreeNode start = findStartNode(query, stats);
            return navigateInternal(query, start, stats);
        } catch (NavigationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NavigationException(query, tree.rootNode() != null ? tree.rootNode().id() : null,
                    NavigationException.ERROR_CODE_LLM_DECISION_FAILED, "Navigation failed", e);
        }
    }

    /** 关键词快速通道：LLM 抽词 → 倒排索引；失败则用原始查询做包含匹配。 */
    private TreeNode findStartNode(String query, LLMUsageStats stats) {
        TreeNode node = null;
        try {
            LLMResponse response = llm.chat(LLMPromptTemplates.extractQueryKeywords(query),
                    SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS);
            stats.add(response);
            if (response != null && response.success()) {
                List<String> keywords = parseKeywordsResponse(response.content());
                if (!keywords.isEmpty()) {
                    node = bestNode(dictionary.matchCandidatesFromKeywords(keywords));
                }
            }
        } catch (RuntimeException e) {
            logger.debug("查询关键词提取失败，回退到原始查询匹配: {}", e.getMessage());
        }

        if (node == null) {
            node = bestNode(dictionary.matchCandidates(query));
        }
        if (node != null) {
            return node;
        }

        TreeNode root = tree.rootNode();
        if (root == null) {
            throw new NavigationException(query, null, NavigationException.ERROR_CODE_TREE_NOT_INITIALIZED,
                    "Semantic tree has no root node");
        }
        return root;
    }

    /** 按得分取候选节点；同分优先叶子，并跳过字典中残留的无效节点。 */
    private TreeNode bestNode(Map<String, Double> candidates) {
        return candidates.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .thenComparingInt(entry -> isLeafNode(entry.getKey()) ? 1 : 0)
                        .reversed())
                .map(entry -> tree.getNode(entry.getKey()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** 同分时优先更具体的叶子节点，避免父类目聚合关键词抢走精确命中。 */
    private boolean isLeafNode(String nodeId) {
        TreeNode node = tree.getNode(nodeId);
        return node != null && node.isLeaf();
    }

    private NavigationResult navigateInternal(String query, TreeNode startNode, LLMUsageStats stats) {
        TreeNode current = startNode;
        List<NavigationPath> history = new ArrayList<>();
        List<String> visited = new ArrayList<>();
        visited.add(current.id());
        history.add(new NavigationPath(query, List.of(current.id()),
                "Starting navigation from " + current.name()));

        int stepCount = 0;
        while (!current.isLeaf() && current.hasChildren()) {
            if (++stepCount > maxNavigationSteps) {
                logger.warn("导航超过最大步数限制 ({})，触发兜底检索", maxNavigationSteps);
                return handleFallback(query, history, stats);
            }

            List<TreeNode> children = childNodes(current);
            if (children.isEmpty()) {
                break;
            }

            LLMResponse response;
            try {
                response = llm.chat(buildNavigationPrompt(query, current, children),
                        SmartCacheStrategy.RequestType.DECIDE_NAVIGATION);
            } catch (RuntimeException e) {
                logger.warn("导航决策调用异常，触发兜底检索", e);
                return handleFallback(query, history, stats);
            }
            stats.add(response);
            if (response == null || !response.success()) {
                logger.warn("导航决策 LLM 调用失败，触发兜底检索: {}",
                        response == null ? "null response" : response.errorMessage());
                return handleFallback(query, history, stats);
            }

            NavigationAction action = parseNavigationResponse(query, response.content(), children);
            switch (action) {
                case NavigationAction.SelectChild(String nodeId, String reasoning, double confidence) -> {
                    if (confidence < MIN_CONFIDENCE) {
                        return handleFallback(query, history, stats);
                    }
                    if (visited.contains(nodeId)) {
                        logger.warn("导航出现环路，节点 {} 已访问，触发兜底检索", nodeId);
                        return handleFallback(query, history, stats);
                    }
                    TreeNode next = tree.getNode(nodeId);
                    if (next == null) {
                        return handleFallback(query, history, stats);
                    }
                    current = next;
                    visited.add(nodeId);
                    List<String> path = new ArrayList<>(history.get(history.size() - 1).visitedNodes());
                    path.add(nodeId);
                    history.add(new NavigationPath(query, path, reasoning));
                }
                case NavigationAction.ExpandMultiple(List<String> nodeIds, String reasoning) ->
                        handleMultiPath(query, nodeIds, history, stats);
                case NavigationAction.FallbackSearch(String reason) ->
                        handleFallback(query, history, stats);
                case NavigationAction.Stop(String reasoning) -> {
                    return new NavigationResult(List.of(), reasoning, history, stats);
                }
            }

            trigger(HookType.NAVIGATION_STEP,
                    HookContext.builder(HookType.NAVIGATION_STEP)
                            .data("query", query)
                            .data("nodeName", current.name())
                            .data("stepCount", stepCount));
        }

        List<DocumentChunk> chunks = loadChunksForNode(current);
        return new NavigationResult(chunks, "Reached node: " + current.name(), history, stats);
    }

    private List<TreeNode> childNodes(TreeNode node) {
        return node.childrenIds().stream()
                .map(tree::getNode)
                .filter(Objects::nonNull)
                .toList();
    }

    private String buildNavigationPrompt(String query, TreeNode node, List<TreeNode> children) {
        List<String> descriptions = children.stream()
                .map(child -> child.name() + " - " + child.description())
                .toList();
        return LLMPromptTemplates.decideNavigation(query, node.name(), node.description(), descriptions);
    }

    /**
     * 关键词兜底：按得分从高到低尝试候选节点，返回第一个能取到文档的节点。
     */
    private NavigationResult handleFallback(String query, List<NavigationPath> history, LLMUsageStats stats) {
        Map<String, Double> candidates = dictionary.matchCandidates(query);
        for (Map.Entry<String, Double> entry : candidates.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .thenComparingInt(item -> isLeafNode(item.getKey()) ? 1 : 0)
                        .reversed())
                .limit(3)
                .toList()) {
            TreeNode node = tree.getNode(entry.getKey());
            if (node == null) {
                continue;
            }
            List<DocumentChunk> chunks = loadChunksForNode(node);
            if (!chunks.isEmpty()) {
                history.add(new NavigationPath(query, List.of(node.id()),
                        "Fallback search result: " + node.name()));
                return new NavigationResult(chunks, "Fallback search result: " + node.name(), history, stats);
            }
        }

        history.add(new NavigationPath(query, List.of(), "Fallback search found no documents"));
        return new NavigationResult(List.of(), "Fallback search failed", history, stats);
    }

    private NavigationResult handleMultiPath(String query, List<String> nodeIds,
                                             List<NavigationPath> history, LLMUsageStats stats) {
        Map<String, DocumentChunk> collected = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            TreeNode node = tree.getNode(nodeId);
            if (node != null) {
                for (DocumentChunk chunk : loadChunksForNode(node)) {
                    collected.putIfAbsent(chunk.id(), chunk);
                }
            }
        }

        history.add(new NavigationPath(query, List.copyOf(nodeIds), "Multi-path expansion"));
        return new NavigationResult(new ArrayList<>(collected.values()),
                "Multi-path expansion across " + nodeIds.size() + " nodes", history, stats);
    }

    private List<DocumentChunk> loadChunksForNode(TreeNode node) {
        if (node.isLeaf()) {
            return loadChunks(node.chunkIds());
        }
        List<DocumentChunk> chunks = new ArrayList<>();
        collectLeafChunks(node, chunks, new HashSet<>());
        // 兼容旧树中“分类节点直接挂 chunkIds”的形态
        return chunks.isEmpty() ? loadChunks(node.chunkIds()) : chunks;
    }

    private void collectLeafChunks(TreeNode node, List<DocumentChunk> out, Set<String> visited) {
        if (node == null || !visited.add(node.id())) {
            return;
        }
        if (node.isLeaf()) {
            out.addAll(loadChunks(node.chunkIds()));
            return;
        }
        for (String childId : node.childrenIds()) {
            collectLeafChunks(tree.getNode(childId), out, visited);
        }
    }

    private List<DocumentChunk> loadChunks(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        List<DocumentChunk> chunks = new ArrayList<>(chunkIds.size());
        for (String chunkId : chunkIds) {
            DocumentChunk chunk = resolveChunk(chunkId);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /** 导航只需要元数据；正文由 SearchResult / 回答链路按需读取。 */
    private DocumentChunk resolveChunk(String chunkId) {
        DocumentChunk chunk = tree.getChunk(chunkId);
        return chunk != null ? chunk : DocumentChunk.metadataOnly(chunkId, "", null, Map.of());
    }

    private NavigationAction parseNavigationResponse(String query, String response, List<TreeNode> childNodes) {
        NavigationDecisionResult result;
        try {
            result = JsonUtils.parseJson(response, NavigationDecisionResult.class);
        } catch (RuntimeException e) {
            throw new NavigationException(query, null,
                    NavigationException.ERROR_CODE_LLM_DECISION_FAILED,
                    "Failed to parse navigation decision: " + JsonUtils.stripCodeFence(response), e);
        }
        if (result == null) {
            throw new NavigationException(query, null,
                    NavigationException.ERROR_CODE_LLM_DECISION_FAILED,
                    "LLM returned null navigation decision");
        }

        String action = result.action() == null ? "" : result.action().trim().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "select_child" -> selectChild(result, childNodes);
            case "expand_multiple" -> expandMultiple(result, childNodes);
            case "fallback_search" -> new NavigationAction.FallbackSearch(result.reasoning());
            case "stop" -> new NavigationAction.Stop(result.reasoning());
            // 兼容没有 action 字段的旧协议
            default -> result.selectedIndex() != null
                    ? selectChild(result, childNodes)
                    : new NavigationAction.Stop(result.reasoning());
        };
    }

    private NavigationAction selectChild(NavigationDecisionResult result, List<TreeNode> childNodes) {
        Integer index = result.selectedIndex();
        if (index == null || index < 0 || index >= childNodes.size()) {
            return new NavigationAction.Stop(result.reasoning());
        }
        double confidence = result.confidence() != null ? result.confidence() : 0.7;
        return new NavigationAction.SelectChild(childNodes.get(index).id(), result.reasoning(), confidence);
    }

    private NavigationAction expandMultiple(NavigationDecisionResult result, List<TreeNode> childNodes) {
        Set<String> ids = new LinkedHashSet<>();
        if (result.selectedIndexes() != null) {
            for (Integer index : result.selectedIndexes()) {
                if (index != null && index >= 0 && index < childNodes.size()) {
                    ids.add(childNodes.get(index).id());
                }
            }
        }
        return ids.isEmpty()
                ? new NavigationAction.Stop(result.reasoning())
                : new NavigationAction.ExpandMultiple(List.copyOf(ids), result.reasoning());
    }

    /** 解析“每行一个关键词”的响应，容忍编号、项目符号、引号和逗号。 */
    private List<String> parseKeywordsResponse(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (String line : response.split("\\R")) {
            String cleaned = line.trim()
                    .replaceFirst("^(?:[-*•]|\\d+[.)、])\\s*", "")
                    .replaceFirst("(?i)^(?:关键词|keywords?)\\s*[:：]\\s*", "")
                    .replace("\"", "")
                    .replace("'", "")
                    .trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            for (String token : cleaned.split("[,，;；]")) {
                String keyword = token.trim().toLowerCase(Locale.ROOT);
                if (!keyword.isEmpty()) {
                    keywords.add(keyword);
                }
            }
            if (keywords.size() >= 8) {
                break;
            }
        }
        return keywords;
    }

    private void trigger(HookType type, HookContext.Builder builder) {
        try {
            hookExecutor.executeHooks(type, builder);
        } catch (RuntimeException e) {
            logger.warn("Hook 执行失败: {}", type, e);
        }
    }
}
