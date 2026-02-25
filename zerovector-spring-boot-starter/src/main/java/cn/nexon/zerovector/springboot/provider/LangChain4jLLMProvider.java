package cn.nexon.zerovector.springboot.provider;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
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
    public LLMResponse comprehendChunk(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            logger.debug("LLM原始响应: {}", result);
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("理解文档块失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse generateSummary(String prompt) {
        String cacheKey = "summary_" + java.util.Objects.hash(prompt);
        if (summaryCache.containsKey(cacheKey)) {
            return LLMResponse.success(summaryCache.get(cacheKey), 0, 0, 0);
        }

        long startTime = System.currentTimeMillis();
        try {
            String summary = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            summaryCache.put(cacheKey, summary);
            return LLMResponse.success(summary, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("生成摘要失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse clusterDocuments(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("聚类失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse extractKeywords(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("提取关键词失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse extractEntities(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("提取实体失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse generateExampleQuestions(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("生成示例问题失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse decideNavigation(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            logger.debug("LLM原始响应: {}", result);
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("导航决策失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse extractQueryKeywords(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.generate(prompt);
            long duration = System.currentTimeMillis() - startTime;
            
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("提取查询关键字失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }
}
