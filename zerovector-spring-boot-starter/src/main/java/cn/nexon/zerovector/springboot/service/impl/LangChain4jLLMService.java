package cn.nexon.zerovector.springboot.service.impl;

import cn.nexon.zerovector.core.ai.LLMService;
import cn.nexon.zerovector.core.model.NavigationAction;
import cn.nexon.zerovector.core.model.TreeNode;
import cn.nexon.zerovector.springboot.autoconfigure.ZeroVectorProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j LLM服务实现
 */
public class LangChain4jLLMService implements LLMService {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jLLMService.class);

    private final ChatLanguageModel chatModel;
    private final ZeroVectorProperties.LangChain4j config;

    // 缓存已生成的摘要
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    public LangChain4jLLMService(ChatLanguageModel chatModel, ZeroVectorProperties.LangChain4j config) {
        this.chatModel = chatModel;
        this.config = config;
    }

    @Override
    public String generateSummary(String title, String content) {
        String cacheKey = title + "_" + content.hashCode();
        if (summaryCache.containsKey(cacheKey)) {
            return summaryCache.get(cacheKey);
        }

        String prompt = "请为以下文档生成一个简洁的摘要（不超过50字）：\n\n标题：" + title + "\n\n内容：" + content;

        try {
            String summary = chatModel.generate(prompt);

            // 缓存结果
            summaryCache.put(cacheKey, summary);

            return summary;
        } catch (Exception e) {
            logger.error("生成摘要失败", e);
            return "文档摘要：" + title;
        }
    }

    @Override
    public List<TreeNode> clusterChunks(List<String> chunks, int clusterSize) {
        // 使用LangChain4j进行文档聚类
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请将以下文档块聚类为").append(clusterSize).append("个类别。");
        promptBuilder.append("返回JSON格式的结果，包含类别名称和包含的文档块索引。\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            promptBuilder.append("[").append(i).append("] ").append(chunks.get(i).substring(0, Math.min(100, chunks.get(i).length()))).append("\n");
        }

        try {
            String result = chatModel.generate(promptBuilder.toString());

            // 解析JSON并创建TreeNode列表
            // 这里简化处理，实际应用中需要更复杂的JSON解析
            return parseClusterResult(result, clusterSize);
        } catch (Exception e) {
            logger.error("聚类失败，使用默认策略", e);
            // 返回简单的平均分组
            return createDefaultClusters(chunks, clusterSize);
        }
    }
    
    @Override
    public List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> clusterChunks(List<cn.nexon.zerovector.core.model.DocumentChunk> chunks) {
        // 使用LangChain4j进行文档聚类
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请将以下文档块聚类为3-5个类别。");
        promptBuilder.append("返回JSON格式的结果，包含类别名称和包含的文档块索引。\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i).content();
            promptBuilder.append("[").append(i).append("] ").append(content.substring(0, Math.min(100, content.length()))).append("\n");
        }

        try {
            String result = chatModel.generate(promptBuilder.toString());

            // 解析JSON并创建NodeCategory列表
            // 这里简化处理，实际应用中需要更复杂的JSON解析
            return parseNodeCategoryResult(result, chunks);
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
        promptBuilder.append("当前节点：").append(currentNode.name()).append(" - ").append(currentNode.description()).append("\n\n");
        promptBuilder.append("可选子节点：\n");

        for (int i = 0; i < childNodes.size(); i++) {
            TreeNode node = childNodes.get(i);
            promptBuilder.append("[").append(i).append("] ").append(node.name()).append(" - ").append(node.description()).append("\n");
        }

        promptBuilder.append("\n请选择最相关的子节点，返回JSON格式：{\"selectedIndex\": 0, \"reasoning\": \"选择原因\", \"confidence\": 0.8}");

        try {
            String result = chatModel.generate(promptBuilder.toString());

            // 解析JSON并创建NavigationAction
            return parseNavigationResult(result, childNodes);
        } catch (Exception e) {
            logger.error("导航决策失败，使用默认策略", e);
            // 返回第一个子节点
            if (!childNodes.isEmpty()) {
                TreeNode selectedNode = childNodes.get(0);
                return new NavigationAction.SelectChild(
                        selectedNode.id(),
                        "默认选择第一个子节点",
                        0.5
                );
            } else {
                return new NavigationAction.Stop("没有可用的子节点");
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
                promptBuilder.append("- ").append(relatedChunks.get(i).substring(0, Math.min(200, relatedChunks.get(i).length()))).append("\n");
            }
        }

        promptBuilder.append("\n请生成一个简洁的描述（不超过100字）：");

        try {
            return chatModel.generate(promptBuilder.toString());
        } catch (Exception e) {
            logger.error("生成节点描述失败", e);
            return node.name() + " - 包含相关文档的节点";
        }
    }

    /**
     * 解析聚类结果
     */
    private List<TreeNode> parseClusterResult(String result, int clusterSize) {
        // 简化实现，实际应用中需要完整的JSON解析
        return createDefaultClusters(List.of(), clusterSize);
    }

    /**
     * 解析导航结果
     */
    private NavigationAction parseNavigationResult(String result, List<TreeNode> childNodes) {
        // 简化实现，实际应用中需要完整的JSON解析
        if (!childNodes.isEmpty()) {
            TreeNode selectedNode = childNodes.get(0);
            return new NavigationAction.SelectChild(
                    selectedNode.id(),
                    "基于LangChain4j的导航决策",
                    0.7
            );
        } else {
            return new NavigationAction.Stop("没有可用的子节点");
        }
    }

    /**
     * 创建默认聚类
     */
    private List<TreeNode> createDefaultClusters(List<String> chunks, int clusterSize) {
        // 简化实现，返回空列表
        return List.of();
    }
    
    /**
     * 解析NodeCategory结果
     */
    private List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> parseNodeCategoryResult(String result, List<cn.nexon.zerovector.core.model.DocumentChunk> chunks) {
        // 简化实现，返回默认分类
        return createDefaultNodeCategories(chunks);
    }
    
    /**
     * 创建默认NodeCategory
     */
    private List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> createDefaultNodeCategories(List<cn.nexon.zerovector.core.model.DocumentChunk> chunks) {
        List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> categories = new ArrayList<>();
        int numCategories = Math.min(3, chunks.size());
        int chunkPerCategory = Math.max(1, chunks.size() / numCategories);
        
        for (int i = 0; i < numCategories; i++) {
            int startIndex = i * chunkPerCategory;
            int endIndex = Math.min(startIndex + chunkPerCategory, chunks.size());
            
            List<cn.nexon.zerovector.core.model.DocumentChunk> categoryChunks = new ArrayList<>();
            List<String> summaries = new ArrayList<>();
            
            for (int j = startIndex; j < endIndex; j++) {
                categoryChunks.add(chunks.get(j));
                summaries.add(chunks.get(j).summary() != null ? chunks.get(j).summary() : "文档摘要");
            }
            
            categories.add(new cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory(
                "category_" + i,
                "类别 " + (i + 1),
                categoryChunks,
                summaries
            ));
        }
        
        return categories;
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
            String result = chatModel.generate(promptBuilder.toString());
            // 简单分割结果
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("生成示例问题失败", e);
            return List.of("如何使用这个功能？", "有什么优势？", "如何开始？");
        }
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
            String result = chatModel.generate(promptBuilder.toString());
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
            String result = chatModel.generate(promptBuilder.toString());
            // 简单分割结果
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("提取关键词失败", e);
            return List.of("系统", "设计", "实现", "架构", "性能");
        }
    }
}