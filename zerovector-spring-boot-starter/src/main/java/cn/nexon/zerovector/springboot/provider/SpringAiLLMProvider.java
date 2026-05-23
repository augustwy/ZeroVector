package cn.nexon.zerovector.springboot.provider;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.util.MD5Util;
import cn.nexon.zerovector.springboot.autoconfigure.ZeroVectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

public class SpringAiLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLLMProvider.class);

    private final ChatClient clusteringClient;
    private final ChatClient navigationClient;

    private final Cache<String, String> summaryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    public SpringAiLLMProvider(ChatModel chatModel, ZeroVectorProperties.Model model) {
        ChatOptions.Builder builder = ChatOptions.builder();
        if (model.maxTokens() > 0) builder.maxTokens(model.maxTokens());
        builder.temperature(model.temperature() >= 0 ? model.temperature() : 0.7);
        this.clusteringClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.clustering()).build()).build();
        this.navigationClient = ChatClient.builder(chatModel).defaultOptions(builder.model(model.navigation()).build()).build();
    }

    @Override
    public LLMResponse comprehendChunk(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            CallResponseSpec callResponseSpec = clusteringClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            String response = callResponse.getResult().getOutput().getText();
            Usage usage = callResponse.getMetadata().getUsage();
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("LLM原始响应: {}", response);
            return LLMResponse.success(response, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("理解文档块失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }

    @Override
    public LLMResponse generateSummary(String prompt) {
        String cacheKey = "summary_" + MD5Util.calculateMD5(prompt);
        String cached = summaryCache.getIfPresent(cacheKey);
        if (cached != null) {
            return LLMResponse.success(cached, 0, 0, 0);
        }

        long startTime = System.currentTimeMillis();
        try {
            CallResponseSpec callResponseSpec = clusteringClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            String summary = callResponse.getResult().getOutput().getText();
            summaryCache.put(cacheKey, summary);
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("生成摘要成功，摘要: {}", summary);
            Usage usage = callResponse.getMetadata().getUsage();
            return LLMResponse.success(summary, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
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
            CallResponseSpec callResponseSpec = clusteringClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            long duration = System.currentTimeMillis() - startTime;
            String response = callResponse.getResult().getOutput().getText();
            logger.debug("LLM原始响应: {}", response);
            Usage usage = callResponse.getMetadata().getUsage();
            return LLMResponse.success(response, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
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
            CallResponseSpec callResponseSpec = clusteringClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            long duration = System.currentTimeMillis() - startTime;
            String response = callResponse.getResult().getOutput().getText();
            logger.debug("LLM原始响应: {}", response);
            Usage usage = callResponse.getMetadata().getUsage();
            return LLMResponse.success(response, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
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
            CallResponseSpec callResponseSpec = clusteringClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            long duration = System.currentTimeMillis() - startTime;
            String response = callResponse.getResult().getOutput().getText();
            logger.debug("LLM原始响应: {}", response);
            Usage usage = callResponse.getMetadata().getUsage();
            return LLMResponse.success(response, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
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
            CallResponseSpec callResponseSpec = clusteringClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            String response = callResponse.getResult().getOutput().getText();
            Usage usage = callResponse.getMetadata().getUsage();
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("LLM原始响应: {}", response);
            return LLMResponse.success(response, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
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
            CallResponseSpec callResponseSpec = navigationClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            long duration = System.currentTimeMillis() - startTime;
            String response = callResponse.getResult().getOutput().getText();
            logger.debug("LLM原始响应: {}", response);
            Usage usage = callResponse.getMetadata().getUsage();
            return LLMResponse.success(response, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
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
            CallResponseSpec callResponseSpec = clusteringClient.prompt().user(prompt).call();
            ChatResponse callResponse = callResponseSpec.chatResponse();
            String response = callResponse.getResult().getOutput().getText();
            Usage usage = callResponse.getMetadata().getUsage();
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("LLM原始响应: {}", response);
            return LLMResponse.success(response, usage.getPromptTokens(), usage.getCompletionTokens(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("提取查询关键字失败", e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }
}
