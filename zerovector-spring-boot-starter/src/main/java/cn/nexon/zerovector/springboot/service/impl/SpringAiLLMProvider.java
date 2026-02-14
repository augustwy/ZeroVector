package cn.nexon.zerovector.springboot.service.impl;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationAction;
import cn.nexon.zerovector.core.model.NodeType;
import cn.nexon.zerovector.core.model.TreeNode;
import cn.nexon.zerovector.core.tree.TreeBuilder;
import cn.nexon.zerovector.springboot.autoconfigure.ZeroVectorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpringAiLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLLMProvider.class);

    private final ChatClient clusteringClient;
    private final ChatClient navigationClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    public record ClusterResult(List<ClusterInfo> clusters) {
    }

    public record ClusterInfo(String name, List<Integer> chunks) {
    }

    public record NavigationDecisionResult(int selectedIndex, String reasoning, double confidence) {
    }

    public SpringAiLLMProvider(ChatModel chatModel, ZeroVectorProperties.Model model) {
        ChatOptions.Builder builder = ChatOptions.builder();
        if (model.maxTokens() > 0) builder.maxTokens(model.maxTokens());
        builder.temperature(model.temperature() > 0 ? model.temperature() : 0.7);
        this.clusteringClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.clustering()).build()).build();
        this.navigationClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.navigation()).build()).build();
    }

    private String extractJsonFromResponse(String response) {
        int jsonStart = response.indexOf("{");
        int jsonEnd = response.lastIndexOf("}");

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }

        jsonStart = response.indexOf("[");
        jsonEnd = response.lastIndexOf("]");

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }

        return response;
    }

    @Override
    public DocumentComprehendResult comprehendChunk(String prompt, String chunk) {
        try {
            String response = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);

            String jsonContent = extractJsonFromResponse(response);
            return objectMapper.readValue(jsonContent, DocumentComprehendResult.class);
        } catch (Exception e) {
            logger.error("理解文档块失败", e);
            return new DocumentComprehendResult("", List.of(), List.of(), List.of());
        }
    }

    @Override
    public String generateSummary(String title, String content) {
        String cacheKey = title + "_" + content.hashCode();
        if (summaryCache.containsKey(cacheKey)) {
            return summaryCache.get(cacheKey);
        }

        String prompt = LLMPromptTemplates.generateSummary(title, content);

        try {
            String summary = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            summaryCache.put(cacheKey, summary);
            logger.debug("生成摘要成功，标题: {}, 摘要: {}", title, summary);

            return summary;
        } catch (Exception e) {
            logger.error("生成摘要失败", e);
            return "文档摘要：" + title;
        }
    }

    @Override
    public List<TreeNode> clusterChunks(List<String> chunks, int clusterSize) {
        String prompt = LLMPromptTemplates.clusterChunks(chunks, clusterSize);

        try {
            String response = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);

            String jsonContent = extractJsonFromResponse(response);
            ClusterResult result = objectMapper.readValue(jsonContent, ClusterResult.class);

            List<TreeNode> treeNodes = new java.util.ArrayList<>();

            for (int i = 0; i < result.clusters().size(); i++) {
                ClusterInfo cluster = result.clusters().get(i);
                List<String> chunkIds = cluster.chunks().stream()
                        .map(index -> "chunk_" + index)
                        .toList();

                TreeNode clusterNode = new TreeNode(
                        "cluster_" + i,
                        cluster.name(),
                        "包含 " + cluster.chunks().size() + " 个相关文档块",
                        NodeType.CATEGORY,
                        List.of(),
                        chunkIds,
                        List.of(),
                        List.of(),
                        List.of()
                );

                treeNodes.add(clusterNode);
            }

            return treeNodes;
        } catch (Exception e) {
            logger.error("聚类失败，使用默认策略", e);
            return createDefaultClusters(chunks, clusterSize);
        }
    }

    @Override
    public List<TreeBuilder.NodeCategory> clusterChunks(List<cn.nexon.zerovector.core.model.DocumentChunk> chunks) {
        List<String> chunkContents = chunks.stream()
                .map(DocumentChunk::content)
                .toList();
        String prompt = LLMPromptTemplates.clusterDocumentChunks(chunkContents);

        try {
            String response = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);

            String jsonContent = extractJsonFromResponse(response);
            ClusterResult result = objectMapper.readValue(jsonContent, ClusterResult.class);

            List<TreeBuilder.NodeCategory> categories = new ArrayList<>();

            for (int i = 0; i < result.clusters().size(); i++) {
                ClusterInfo cluster = result.clusters().get(i);

                List<cn.nexon.zerovector.core.model.DocumentChunk> categoryChunks = new ArrayList<>();
                List<String> summaries = new ArrayList<>();

                for (Integer index : cluster.chunks()) {
                    if (index < chunks.size()) {
                        cn.nexon.zerovector.core.model.DocumentChunk chunk = chunks.get(index);
                        categoryChunks.add(chunk);
                        summaries.add(chunk.summary() != null ? chunk.summary() : "文档摘要");
                    }
                }

                categories.add(new TreeBuilder.NodeCategory(
                        "category_" + i,
                        cluster.name(),
                        categoryChunks,
                        summaries
                ));
            }

            return categories;
        } catch (Exception e) {
            logger.error("聚类失败，使用默认策略", e);
            return createDefaultNodeCategories(chunks);
        }
    }

    @Override
    public NavigationAction decideNavigation(String query, TreeNode currentNode, List<TreeNode> childNodes) {
        List<String> childNodeDescriptions = childNodes.stream()
                .map(node -> node.name() + " - " + node.description())
                .toList();
        String prompt = LLMPromptTemplates.decideNavigation(
                query,
                currentNode.name(),
                currentNode.description(),
                childNodeDescriptions
        );

        try {
            String response = navigationClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);

            String jsonContent = extractJsonFromResponse(response);
            NavigationDecisionResult result = objectMapper.readValue(jsonContent, NavigationDecisionResult.class);

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
            logger.error("导航决策失败，使用默认策略", e);
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

    @Override
    public String generateNodeDescription(TreeNode node, List<String> relatedChunks) {
        String prompt = LLMPromptTemplates.generateNodeDescription(node.name(), relatedChunks);

        try {
            String description = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("生成节点描述成功，节点: {}, 描述: {}", node.name(), description);
            return description;
        } catch (Exception e) {
            logger.error("生成节点描述失败", e);
            return node.name() + " - 包含相关文档的节点";
        }
    }

    @Override
    public List<String> extractEntities(String content) {
        String prompt = LLMPromptTemplates.extractEntities(content);

        try {
            String result = clusteringClient.prompt().user(prompt).call().content();
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("提取实体失败", e);
            return List.of("Java", "Spring", "数据库", "API", "系统");
        }
    }

    @Override
    public List<String> extractKeywords(String content) {
        String prompt = LLMPromptTemplates.extractKeywords(content);

        try {
            String result = clusteringClient.prompt().user(prompt).call().content();
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("提取关键词失败", e);
            return List.of("系统", "设计", "实现", "架构", "性能");
        }
    }

    @Override
    public List<String> generateExampleQuestions(String content) {
        String prompt = LLMPromptTemplates.generateExampleQuestions(content);

        try {
            String result = clusteringClient.prompt().user(prompt).call().content();
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("生成示例问题失败", e);
            return List.of("如何使用这个功能？", "有什么优势？", "如何开始？");
        }
    }

    private List<TreeNode> createDefaultClusters(List<String> chunks, int clusterSize) {
        List<TreeNode> clusters = new java.util.ArrayList<>();
        int chunkPerCluster = Math.max(1, chunks.size() / clusterSize);

        for (int i = 0; i < clusterSize; i++) {
            int startIndex = i * chunkPerCluster;
            int endIndex = Math.min(startIndex + chunkPerCluster, chunks.size());

            List<String> chunkIds = new java.util.ArrayList<>();
            for (int j = startIndex; j < endIndex; j++) {
                chunkIds.add("chunk_" + j);
            }

            TreeNode clusterNode = new TreeNode(
                    "cluster_" + i,
                    "类别 " + (i + 1),
                    "包含 " + (endIndex - startIndex) + " 个文档块",
                    NodeType.CATEGORY,
                    List.of(),
                    chunkIds,
                    List.of(),
                    List.of(),
                    List.of()
            );

            clusters.add(clusterNode);
        }

        return clusters;
    }

    private List<TreeBuilder.NodeCategory> createDefaultNodeCategories(List<DocumentChunk> chunks) {
        List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> categories = new ArrayList<>();

        if (chunks.size() <= 3) {
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                List<DocumentChunk> categoryChunks = List.of(chunk);
                List<String> summaries = List.of(chunk.summary() != null ? chunk.summary() : "文档摘要");

                categories.add(new TreeBuilder.NodeCategory(
                        "category_" + i,
                        "文档 " + (i + 1),
                        categoryChunks,
                        summaries
                ));
            }
        } else {
            int numCategories = Math.min(3, chunks.size());
            int chunkPerCategory = Math.max(1, chunks.size() / numCategories);

            for (int i = 0; i < numCategories; i++) {
                int startIndex = i * chunkPerCategory;
                int endIndex = Math.min(startIndex + chunkPerCategory, chunks.size());

                List<DocumentChunk> categoryChunks = new ArrayList<>();
                List<String> summaries = new ArrayList<>();

                for (int j = startIndex; j < endIndex; j++) {
                    categoryChunks.add(chunks.get(j));
                    summaries.add(chunks.get(j).summary() != null ? chunks.get(j).summary() : "文档摘要");
                }

                categories.add(new TreeBuilder.NodeCategory(
                        "category_" + i,
                        "类别 " + (i + 1),
                        categoryChunks,
                        summaries
                ));
            }
        }

        return categories;
    }
}
