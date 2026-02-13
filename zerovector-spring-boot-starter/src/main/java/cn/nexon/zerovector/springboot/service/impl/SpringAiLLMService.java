package cn.nexon.zerovector.springboot.service.impl;

import cn.nexon.zerovector.core.ai.LLMService;
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

/**
 * Spring AI LLM服务实现
 * 使用适配器模式支持多种模型提供商
 */
public class SpringAiLLMService implements LLMService {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLLMService.class);

    private final ChatClient clusteringClient;
    private final ChatClient navigationClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    /**
     * 聚类结果记录
     */
    public record ClusterResult(List<ClusterInfo> clusters) {
    }

    /**
     * 聚类信息记录
     */
    public record ClusterInfo(String name, List<Integer> chunks) {
    }

    /**
     * 导航决策结果记录
     */
    public record NavigationDecisionResult(int selectedIndex, String reasoning, double confidence) {
    }

    public SpringAiLLMService(ChatModel chatModel, ZeroVectorProperties.Model model) {
        ChatOptions.Builder builder = ChatOptions.builder();
        if (model.maxTokens() > 0) builder.maxTokens(model.maxTokens());
        builder.temperature(model.temperature() > 0 ? model.temperature() : 0.7);
        this.clusteringClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.clustering()).build()).build();
        this.navigationClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.navigation()).build()).build();
    }

    /**
     * 结构化输出方法，支持将LLM输出直接映射为Java对象
     *
     * @param chatClient LLM客户端
     * @param prompt     提示词
     * @param clazz      目标类型
     * @return 映射后的对象
     */
    private <T> T completeJson(ChatClient chatClient, String prompt, Class<T> clazz) {
        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);

            // 尝试提取JSON部分
            String jsonContent = extractJsonFromResponse(response);

            return objectMapper.readValue(jsonContent, clazz);
        } catch (Exception e) {
            logger.error("结构化输出解析失败", e);
            throw new RuntimeException("结构化输出解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从响应中提取JSON内容
     */
    private String extractJsonFromResponse(String response) {
        // 查找JSON开始和结束位置
        int jsonStart = response.indexOf("{");
        int jsonEnd = response.lastIndexOf("}");

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }

        // 如果没找到，尝试查找JSON数组
        jsonStart = response.indexOf("[");
        jsonEnd = response.lastIndexOf("]");

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }

        // 如果都找不到，返回原始响应
        return response;
    }

    @Override
    public String generateSummary(String title, String content) {
        String cacheKey = title + "_" + content.hashCode();
        if (summaryCache.containsKey(cacheKey)) {
            return summaryCache.get(cacheKey);
        }

        String prompt = "请为以下文档生成一个简洁的摘要（不超过50字）：\n\n标题：" + title + "\n\n内容：" + content;

        try {
            String summary = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // 缓存结果
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
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请将以下文档块聚类为").append(clusterSize).append("个类别。");
        promptBuilder.append("分析每个文档块的内容，根据主题相似性进行分组。\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            promptBuilder.append("[").append(i).append("] ")
                    .append(chunks.get(i).substring(0, Math.min(200, chunks.get(i).length())))
                    .append("\n");
        }

        promptBuilder.append("\n请按以下JSON格式返回结果：\n");
        promptBuilder.append("{\n");
        promptBuilder.append("  \"clusters\": [\n");
        promptBuilder.append("    {\n");
        promptBuilder.append("      \"name\": \"类别名称\",\n");
        promptBuilder.append("      \"chunks\": [0, 1, 2]\n");
        promptBuilder.append("    }\n");
        promptBuilder.append("  ]\n");
        promptBuilder.append("}\n");

        try {
            // 使用结构化输出方法
            ClusterResult result = completeJson(clusteringClient, promptBuilder.toString(), ClusterResult.class);

            // 将聚类结果转换为TreeNode列表
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
                        List.of(), // keyEntities
                        List.of(), // keywords
                        List.of()  // exampleQuestions
                );

                treeNodes.add(clusterNode);
            }

            return treeNodes;
        } catch (Exception e) {
            logger.error("聚类失败，使用默认策略", e);
            // 返回简单的平均分组
            return createDefaultClusters(chunks, clusterSize);
        }
    }

    @Override
    public List<TreeBuilder.NodeCategory> clusterChunks(List<cn.nexon.zerovector.core.model.DocumentChunk> chunks) {
        // 使用Spring AI进行文档聚类
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请将以下文档块按内容主题相似性聚类为3-5个类别。");
        promptBuilder.append("重要：只有内容真正相关的文档才能放在同一类别中。");
        promptBuilder.append("如果文档内容完全不相关，请将它们分到不同的类别，即使某些类别可能只有一个文档。");
        promptBuilder.append("分析每个文档块的内容，根据主题相似性进行分组。\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i).content();
            promptBuilder.append("[").append(i).append("] ")
                    .append(content.substring(0, Math.min(200, content.length())))
                    .append("\n");
        }

        promptBuilder.append("\n请按以下JSON格式返回结果：\n");
        promptBuilder.append("{\n");
        promptBuilder.append("  \"clusters\": [\n");
        promptBuilder.append("    {\n");
        promptBuilder.append("      \"name\": \"类别名称\",\n");
        promptBuilder.append("      \"chunks\": [0, 1, 2]\n");
        promptBuilder.append("    }\n");
        promptBuilder.append("  ]\n");
        promptBuilder.append("}\n");

        try {
            // 使用结构化输出方法
            ClusterResult result = completeJson(clusteringClient, promptBuilder.toString(), ClusterResult.class);

            // 将聚类结果转换为NodeCategory列表
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
            // 返回简单的平均分组
            return createDefaultNodeCategories(chunks);
        }
    }

    @Override
    public NavigationAction decideNavigation(String query, TreeNode currentNode, List<TreeNode> childNodes) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("用户查询：").append(query).append("\n\n");
        promptBuilder.append("当前节点：").append(currentNode.name()).append(" - ")
                .append(currentNode.description()).append("\n\n");
        promptBuilder.append("可选子节点：\n");

        for (int i = 0; i < childNodes.size(); i++) {
            TreeNode node = childNodes.get(i);
            promptBuilder.append("[").append(i).append("] ").append(node.name())
                    .append(" - ").append(node.description()).append("\n");
        }

        promptBuilder.append("\n请分析用户查询，选择最相关的子节点。");
        promptBuilder.append("返回JSON格式：{\"selectedIndex\": 0, \"reasoning\": \"选择原因\", \"confidence\": 0.8}");
        promptBuilder.append("如果没有相关子节点，请返回{\"selectedIndex\": -1, \"reasoning\": \"没有相关子节点\", \"confidence\": 0.0}");

        try {
            // 使用结构化输出方法
            NavigationDecisionResult result = completeJson(navigationClient, promptBuilder.toString(), NavigationDecisionResult.class);

            // 处理结果
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
            // 返回第一个子节点
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
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请为以下节点生成描述：\n\n");
        promptBuilder.append("节点名称：").append(node.name()).append("\n");

        if (relatedChunks != null && !relatedChunks.isEmpty()) {
            promptBuilder.append("相关文档内容：\n");
            for (int i = 0; i < Math.min(3, relatedChunks.size()); i++) {
                promptBuilder.append("- ").append(relatedChunks.get(i)
                                .substring(0, Math.min(200, relatedChunks.get(i).length())))
                        .append("\n");
            }
        }

        promptBuilder.append("\n请生成一个简洁的描述（不超过100字）：");

        try {
            String description = clusteringClient.prompt()
                    .user(promptBuilder.toString())
                    .call()
                    .content();

            logger.debug("生成节点描述成功，节点: {}, 描述: {}", node.name(), description);
            return description;
        } catch (Exception e) {
            logger.error("生成节点描述失败", e);
            return node.name() + " - 包含相关文档的节点";
        }
    }

    /**
     * 创建默认聚类
     */
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
                    List.of(), // keyEntities
                    List.of(), // keywords
                    List.of()  // exampleQuestions
            );

            clusters.add(clusterNode);
        }

        return clusters;
    }

    /**
     * 创建默认NodeCategory
     */
    private List<TreeBuilder.NodeCategory> createDefaultNodeCategories(List<DocumentChunk> chunks) {
        List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> categories = new ArrayList<>();
        
        // 对于少量文档，每个文档创建一个类别，确保不相关文档不会被错误分组
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
            // 对于较多文档，使用简单的平均分组
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

    @Override
    public List<String> extractEntities(String content) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请从以下内容中提取5个重要的实体（如人名、地名、组织名、产品名等）：\n\n");
        promptBuilder.append(content.substring(0, Math.min(500, content.length())));
        if (content.length() > 500) {
            promptBuilder.append("...");
        }
        promptBuilder.append("\n\n请只列出实体，每行一个：");

        try {
            String result = clusteringClient.prompt().user(promptBuilder.toString()).call().content();
            // 简单分割结果
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("提取实体失败", e);
            return List.of("Java", "Spring", "数据库", "API", "系统");
        }
    }

    @Override
    public List<String> extractKeywords(String content) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请从以下内容中提取5个关键词：\n\n");
        promptBuilder.append(content.substring(0, Math.min(500, content.length())));
        if (content.length() > 500) {
            promptBuilder.append("...");
        }
        promptBuilder.append("\n\n请只列出关键词，每行一个：");

        try {
            String result = clusteringClient.prompt().user(promptBuilder.toString()).call().content();
            // 简单分割结果
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("提取关键词失败", e);
            return List.of("系统", "设计", "实现", "架构", "性能");
        }
    }

    @Override
    public List<String> generateExampleQuestions(String content) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请为以下内容生成3个示例问题：\n\n");
        promptBuilder.append(content.substring(0, Math.min(500, content.length())));
        if (content.length() > 500) {
            promptBuilder.append("...");
        }
        promptBuilder.append("\n\n请生成3个简洁的问题，每行一个：");

        try {
            String result = clusteringClient.prompt().user(promptBuilder.toString()).call().content();
            // 简单分割结果
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("生成示例问题失败", e);
            return List.of("如何使用这个功能？", "有什么优势？", "如何开始？");
        }
    }
}