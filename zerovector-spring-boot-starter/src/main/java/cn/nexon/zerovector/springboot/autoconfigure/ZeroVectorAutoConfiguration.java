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

package cn.nexon.zerovector.springboot.autoconfigure;

import cn.nexon.zerovector.core.KnowledgeBaseManager;
import cn.nexon.zerovector.core.ai.CacheConfig;
import cn.nexon.zerovector.core.ai.CachedLLMProvider;
import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.RateLimitedLLMProvider;
import cn.nexon.zerovector.core.ai.SimilarityConfig;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.hook.LifecycleHook;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookRegistry;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.springboot.SemanticHub;
import cn.nexon.zerovector.springboot.provider.LangChain4jLLMProvider;
import cn.nexon.zerovector.springboot.provider.SpringAiLLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ZeroVector Spring Boot 自动配置类
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zerovector", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ZeroVectorProperties.class)
public class ZeroVectorAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ZeroVectorAutoConfiguration.class);

    // 检测到 Spring AI 类路径
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.ai.chat.model.ChatModel.class)
    static class SpringAiConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "springAiLLMService")
        LLMProvider springAiLLMService(org.springframework.ai.chat.model.ChatModel chatModel, ZeroVectorProperties config) {
            return new SpringAiLLMProvider(chatModel, config.getModel());
        }
    }
    
    // 检测到 Langchain4j 类路径
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(dev.langchain4j.model.chat.ChatModel.class)
    static class Langchain4jConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "langChain4jLLMService")
        LLMProvider langChain4jLLMService(dev.langchain4j.model.chat.ChatModel chatModel, ZeroVectorProperties config) {
            return new LangChain4jLLMProvider(chatModel);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(LLMProvider.class)
    static class CachedLLMConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "cachedLLMProvider")
        LLMProvider cachedLLMProvider(LLMProvider delegate, ZeroVectorProperties config) {
            if (config.getRateLimit().isEnabled()) {
                ZeroVectorProperties.RateLimit rl = config.getRateLimit();
                logger.info("LLM 限流已启用: {} 次 / {} 秒", rl.getMaxRequests(), rl.getWindowSeconds());
                delegate = new RateLimitedLLMProvider(delegate, rl.getMaxRequests(), rl.getWindowSeconds());
            }
            return createCachedLLMProvider(delegate, config);
        }
    }

    /**
     * 创建缓存的LLM提供者
     */
    private static LLMProvider createCachedLLMProvider(LLMProvider delegate, ZeroVectorProperties config) {
        if (!config.getCache().isEnabled()) {
            logger.debug("LLM缓存已禁用");
            return delegate;
        }
        
        // 相似匹配配置按实例注入 CachedLLMProvider，不再写入全局静态状态
        SimilarityConfig similarityConfig = SimilarityConfig.DEFAULT;
        if (config.getCache() != null && config.getCache().getSmartCache() != null) {
            ZeroVectorProperties.SmartCacheStrategyConfig smartCacheConfig = config.getCache().getSmartCache();
            similarityConfig = new SimilarityConfig(
                smartCacheConfig.getSimilarityThreshold(),
                smartCacheConfig.getMaxSimilarityLength(),
                smartCacheConfig.isEnableSimilarityMatching());
            logger.debug("SmartCacheStrategy配置已应用: similarityThreshold={}, maxSimilarityLength={}, enableSimilarityMatching={}", 
                smartCacheConfig.getSimilarityThreshold(), 
                smartCacheConfig.getMaxSimilarityLength(), 
                smartCacheConfig.isEnableSimilarityMatching());
        }
        
        Map<SmartCacheStrategy.RequestType, CacheConfig> cacheConfigs = new ConcurrentHashMap<>();
        cacheConfigs.put(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK, 
            toCacheConfig(config.getCache().getComprehendChunk()));
        cacheConfigs.put(SmartCacheStrategy.RequestType.GENERATE_SUMMARY, 
            toCacheConfig(config.getCache().getGenerateSummary()));
        cacheConfigs.put(SmartCacheStrategy.RequestType.CLUSTER_DOCUMENTS, 
            toCacheConfig(config.getCache().getClusterDocuments()));
        cacheConfigs.put(SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, 
            toCacheConfig(config.getCache().getExtractKeywords()));
        cacheConfigs.put(SmartCacheStrategy.RequestType.EXTRACT_ENTITIES, 
            toCacheConfig(config.getCache().getExtractEntities()));
        cacheConfigs.put(SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS, 
            toCacheConfig(config.getCache().getGenerateExampleQuestions()));
        cacheConfigs.put(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION,
            toCacheConfig(config.getCache().getDecideNavigation()));
        cacheConfigs.put(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS,
            toCacheConfig(config.getCache().getExtractKeywords()));

        CachedLLMProvider provider = new CachedLLMProvider(delegate, cacheConfigs, similarityConfig);
        // 设置相似性搜索的最大条目数
        provider.setMaxSearchItems(100); // 后续可以从配置文件中读取
        
        logger.debug("LLM缓存已启用，配置: {}", config.getCache());
        return provider;
    }
    
    /**
     * 将ZeroVectorProperties.CacheConfig转换为CacheConfig
     */
    private static CacheConfig toCacheConfig(ZeroVectorProperties.CacheConfig config) {
        return new CacheConfig(config.getMaxSize(), config.getExpireAfterAccess(), config.getTimeUnit(), true, "");
    }

    /**
     * 创建钩子注册表
     */
    @Bean
    @ConditionalOnMissingBean
    HookRegistry hookRegistry() {
        return new HookRegistry();
    }

    /**
     * 创建钩子执行器
     */
    @Bean
    @ConditionalOnMissingBean
    HookExecutor hookExecutor(HookRegistry hookRegistry, ObjectProvider<LifecycleHook> hooksProvider,
                              ZeroVectorProperties properties) {
        ZeroVectorProperties.Hook hookConfig = properties.getHook();
        
        if (hookConfig.isEnabled()) {
            hooksProvider.forEach(hook -> {
                hookRegistry.register(hook);
            });
            
            List<String> hooks = hookConfig.getHooks();
            if (hooks != null && !hooks.isEmpty()) {
                for (String hookClassName : hooks) {
                    try {
                        Class<?> hookClass = Class.forName(hookClassName);
                        if (LifecycleHook.class.isAssignableFrom(hookClass)) {
                            LifecycleHook hook = (LifecycleHook) hookClass.getDeclaredConstructor().newInstance();
                            hookRegistry.register(hook);
                        } else {
                            logger.warn("类 {} 不是 LifecycleHook 的实现类，跳过", hookClassName);
                        }
                    } catch (Exception e) {
                        logger.warn("无法实例化钩子类 {}: {}", hookClassName, e.getMessage());
                    }
                }
            }
        }
        
        return new DefaultHookExecutor(hookRegistry);
    }

    /**
     * 创建文档理解器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zerovector", name = "enabled", havingValue = "true", matchIfMissing = true)
    DocumentComprehender documentComprehender(LLMProvider llmProvider, ZeroVectorProperties properties,
                                              HookExecutor hookExecutor) {
        ZeroVectorProperties.LLMContext llmContext = properties.getLlmContext();
        return new DocumentComprehender(llmProvider, llmContext.getMaxChunkTokens(), hookExecutor);
    }

    /**
     * 创建知识库管理器
     * 
     * <p>知识库管理器统一管理存储，存储路径为 {storageBasePath}/{knowledgeBaseName}/
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zerovector", name = "enabled", havingValue = "true", matchIfMissing = true)
    KnowledgeBaseManager knowledgeBaseManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                                              ZeroVectorProperties properties, HookExecutor hookExecutor) {
        logger.debug("创建知识库管理器，基础存储路径: {}", properties.getStorageBasePath());
        
        KnowledgeBaseManager manager = new KnowledgeBaseManager(
            llmProvider, 
            documentComprehender,
            properties.getConcurrency(),
            properties.getStorageBasePath(),
            hookExecutor
        );
        
        try {
            manager.initialize();
            return manager;
        } catch (Exception e) {
            logger.error("知识库管理器初始化失败", e);
            throw new RuntimeException("知识库管理器初始化失败", e);
        }
    }

    /**
     * 高级API
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zerovector", name = "enabled", havingValue = "true", matchIfMissing = true)
    SemanticHub semanticHub(KnowledgeBaseManager knowledgeBaseManager) {
        return new SemanticHub(knowledgeBaseManager);
    }
}
