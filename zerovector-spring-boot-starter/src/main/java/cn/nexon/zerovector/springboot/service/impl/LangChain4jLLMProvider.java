package cn.nexon.zerovector.springboot.service.impl;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.springboot.autoconfigure.ZeroVectorProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public String comprehendChunk(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            logger.debug("LLM原始响应: {}", result);
            return result;
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
            String summary = chatModel.generate(prompt);
            summaryCache.put(cacheKey, summary);
            return summary;
        } catch (Exception e) {
            logger.error("生成摘要失败", e);
            return "文档摘要";
        }
    }

    @Override
    public String clusterChunks(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            return result;
        } catch (Exception e) {
            logger.error("聚类失败", e);
            return "{}";
        }
    }

    @Override
    public String clusterDocuments(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            return result;
        } catch (Exception e) {
            logger.error("聚类失败", e);
            return "{}";
        }
    }

    @Override
    public String extractKeywords(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            return result;
        } catch (Exception e) {
            logger.error("提取关键词失败", e);
            return "系统\n设计\n实现\n架构\n性能";
        }
    }

    @Override
    public String extractEntities(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            return result;
        } catch (Exception e) {
            logger.error("提取实体失败", e);
            return "Java\nSpring\n数据库\nAPI\n系统";
        }
    }

    @Override
    public String generateExampleQuestions(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            return result;
        } catch (Exception e) {
            logger.error("生成示例问题失败", e);
            return "如何使用这个功能？\n有什么优势？\n如何开始？";
        }
    }

    @Override
    public String decideNavigation(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            return result;
        } catch (Exception e) {
            logger.error("导航决策失败", e);
            return "{}";
        }
    }

    @Override
    public String generateNodeDescription(String prompt) {
        try {
            return chatModel.generate(prompt);
        } catch (Exception e) {
            logger.error("生成节点描述失败", e);
            return "包含相关文档的节点";
        }
    }
}
