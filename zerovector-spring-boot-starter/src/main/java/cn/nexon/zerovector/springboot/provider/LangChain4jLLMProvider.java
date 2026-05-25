package cn.nexon.zerovector.springboot.provider;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.util.MD5Util;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

public class LangChain4jLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jLLMProvider.class);

    private final ChatModel chatModel;

    private final Cache<String, String> summaryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    public LangChain4jLLMProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    private LLMResponse execute(String prompt, String operationName) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.chat(prompt);
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("LLM原始响应: {}", result);
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("{}失败", operationName, e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse comprehendChunk(String prompt) {
        return execute(prompt, "理解文档块");
    }

    @Override
    public LLMResponse generateSummary(String prompt) {
        String cacheKey = "summary_" + MD5Util.calculateMD5(prompt);
        String cached = summaryCache.getIfPresent(cacheKey);
        if (cached != null) {
            return LLMResponse.success(cached, 0, 0, 0);
        }
        LLMResponse response = execute(prompt, "生成摘要");
        if (response.success()) {
            summaryCache.put(cacheKey, response.content());
        }
        return response;
    }

    @Override
    public LLMResponse clusterDocuments(String prompt) {
        return execute(prompt, "聚类文档");
    }

    @Override
    public LLMResponse extractKeywords(String prompt) {
        return execute(prompt, "提取关键词");
    }

    @Override
    public LLMResponse extractEntities(String prompt) {
        return execute(prompt, "提取实体");
    }

    @Override
    public LLMResponse generateExampleQuestions(String prompt) {
        return execute(prompt, "生成示例问题");
    }

    @Override
    public LLMResponse decideNavigation(String prompt) {
        return execute(prompt, "导航决策");
    }

    @Override
    public LLMResponse extractQueryKeywords(String prompt) {
        return execute(prompt, "提取查询关键字");
    }
}
