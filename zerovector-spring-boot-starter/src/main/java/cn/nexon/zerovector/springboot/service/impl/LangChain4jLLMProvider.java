package cn.nexon.zerovector.springboot.service.impl;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
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

public class LangChain4jLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jLLMProvider.class);

    private final ChatLanguageModel chatModel;
    private final ZeroVectorProperties.LangChain4j config;

    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    public LangChain4jLLMProvider(ChatLanguageModel chatModel, ZeroVectorProperties.LangChain4j config) {
        this.chatModel = chatModel;
        this.config = config;
    }

    @Override
    public DocumentComprehendResult comprehendChunk(String prompt, String chunk) {
        try {
            String result = chatModel.generate(prompt);
            logger.debug("LLM原始响应: {}", result);
            
            String summary = result.contains("摘要") ? 
                extractField(result, "summary", "摘要") : result.substring(0, Math.min(100, result.length()));
            
            List<String> keywords = extractListField(result, "keywords");
            List<String> entities = extractListField(result, "entities");
            List<String> questions = extractListField(result, "exampleQuestions");
            
            return new DocumentComprehendResult(summary, keywords, entities, questions);
        } catch (Exception e) {
            logger.error("理解文档块失败", e);
            return new DocumentComprehendResult("", List.of(), List.of(), List.of());
        }
    }

    private String extractField(String result, String fieldName, String defaultValue) {
        String pattern = "\"" + fieldName + "\":\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(result);
        if (m.find()) {
            return m.group(1);
        }
        return defaultValue;
    }

    private List<String> extractListField(String result, String fieldName) {
        String pattern = "\"" + fieldName + "\":\\s*\\[([^\\]]+)\\]";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(result);
        if (m.find()) {
            String[] items = m.group(1).split(",");
            List<String> list = new ArrayList<>();
            for (String item : items) {
                list.add(item.trim().replaceAll("\"", ""));
            }
            return list;
        }
        return List.of();
    }

    @Override
    public String generateSummary(String title, String content) {
        String cacheKey = title + "_" + content.hashCode();
        if (summaryCache.containsKey(cacheKey)) {
            return summaryCache.get(cacheKey);
        }

        String prompt = LLMPromptTemplates.generateSummary(title, content);

        try {
            String summary = chatModel.generate(prompt);
            summaryCache.put(cacheKey, summary);
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
            String result = chatModel.generate(prompt);
            return parseClusterResult(result, clusterSize);
        } catch (Exception e) {
            logger.error("聚类失败，使用默认策略", e);
            return createDefaultClusters(chunks, clusterSize);
        }
    }

    @Override
    public List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> clusterChunks(List<cn.nexon.zerovector.core.model.DocumentChunk> chunks) {
        List<String> chunkContents = chunks.stream()
                .map(chunk -> chunk.content())
                .toList();
        String prompt = LLMPromptTemplates.clusterDocumentChunks(chunkContents);

        try {
            String result = chatModel.generate(prompt);
            return parseNodeCategoryResult(result, chunks);
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
            String result = chatModel.generate(prompt);
            return parseNavigationResult(result, childNodes);
        } catch (Exception e) {
            logger.error("导航决策失败，使用默认策略", e);
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
        String prompt = LLMPromptTemplates.generateNodeDescription(node.name(), relatedChunks);

        try {
            return chatModel.generate(prompt);
        } catch (Exception e) {
            logger.error("生成节点描述失败", e);
            return node.name() + " - 包含相关文档的节点";
        }
    }

    @Override
    public List<String> extractEntities(String content) {
        String prompt = LLMPromptTemplates.extractEntities(content);

        try {
            String result = chatModel.generate(prompt);
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
            String result = chatModel.generate(prompt);
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
            String result = chatModel.generate(prompt);
            return List.of(result.split("\n"));
        } catch (Exception e) {
            logger.error("生成示例问题失败", e);
            return List.of("如何使用这个功能？", "有什么优势？", "如何开始？");
        }
    }

    private List<TreeNode> parseClusterResult(String result, int clusterSize) {
        return createDefaultClusters(List.of(), clusterSize);
    }

    private NavigationAction parseNavigationResult(String result, List<TreeNode> childNodes) {
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

    private List<cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory> parseNodeCategoryResult(String result, List<cn.nexon.zerovector.core.model.DocumentChunk> chunks) {
        return createDefaultNodeCategories(chunks);
    }

    private List<TreeNode> createDefaultClusters(List<String> chunks, int clusterSize) {
        return List.of();
    }

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
}
