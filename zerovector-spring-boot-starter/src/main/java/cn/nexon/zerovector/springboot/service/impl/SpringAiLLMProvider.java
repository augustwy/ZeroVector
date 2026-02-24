package cn.nexon.zerovector.springboot.service.impl;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.springboot.autoconfigure.ZeroVectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpringAiLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLLMProvider.class);

    private final ChatClient clusteringClient;
    private final ChatClient navigationClient;

    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    public SpringAiLLMProvider(ChatModel chatModel, ZeroVectorProperties.Model model) {
        ChatOptions.Builder builder = ChatOptions.builder();
        if (model.maxTokens() > 0) builder.maxTokens(model.maxTokens());
        builder.temperature(model.temperature() > 0 ? model.temperature() : 0.7);
        this.clusteringClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.clustering()).build()).build();
        this.navigationClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.navigation()).build()).build();
    }

    @Override
    public String comprehendChunk(String prompt) {
        try {
            String response = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);
            return response;
        } catch (Exception e) {
            logger.error("理解文档块失败", e);
            return "";
        }
    }

    @Override
    public String generateSummary(String prompt) {
        String cacheKey = "summary_" + java.util.Objects.hash(prompt);
        if (summaryCache.containsKey(cacheKey)) {
            return summaryCache.get(cacheKey);
        }

        try {
            String summary = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            summaryCache.put(cacheKey, summary);
            logger.debug("生成摘要成功，摘要: {}", summary);

            return summary;
        } catch (Exception e) {
            logger.error("生成摘要失败", e);
            return "文档摘要";
        }
    }

    @Override
    public String clusterDocuments(String prompt) {
        try {
            String response = clusteringClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);
            return response;
        } catch (Exception e) {
            logger.error("聚类失败", e);
            return "{}";
        }
    }

    @Override
    public String extractKeywords(String prompt) {
        try {
            String result = clusteringClient.prompt().user(prompt).call().content();
            return result;
        } catch (Exception e) {
            logger.error("提取关键词失败", e);
            return "系统\n设计\n实现\n架构\n性能";
        }
    }

    @Override
    public String extractEntities(String prompt) {
        try {
            String result = clusteringClient.prompt().user(prompt).call().content();
            return result;
        } catch (Exception e) {
            logger.error("提取实体失败", e);
            return "Java\nSpring\n数据库\nAPI\n系统";
        }
    }

    @Override
    public String generateExampleQuestions(String prompt) {
        try {
            String result = clusteringClient.prompt().user(prompt).call().content();
            return result;
        } catch (Exception e) {
            logger.error("生成示例问题失败", e);
            return "如何使用这个功能？\n有什么优势？\n如何开始？";
        }
    }

    @Override
    public String decideNavigation(String prompt) {
        try {
            String response = navigationClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM原始响应: {}", response);
            return response;
        } catch (Exception e) {
            logger.error("导航决策失败", e);
            return "{}";
        }
    }

    @Override
    public String extractQueryKeywords(String prompt) {
        try {
            String result = clusteringClient.prompt().user(prompt).call().content();
            return result;
        } catch (Exception e) {
            logger.error("提取查询关键字失败", e);
            return "";
        }
    }
}
