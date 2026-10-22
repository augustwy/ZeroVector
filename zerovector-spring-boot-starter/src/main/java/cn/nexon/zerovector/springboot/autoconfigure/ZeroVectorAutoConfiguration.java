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
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookRegistry;
import cn.nexon.zerovector.core.hook.LifecycleHook;
import cn.nexon.zerovector.springboot.SemanticHub;
import cn.nexon.zerovector.springboot.provider.LangChain4jLLMProvider;
import cn.nexon.zerovector.springboot.provider.SpringAiLLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZeroVector Spring Boot 自动配置。
 *
 * <p>LLM 提供者选择顺序：用户自定义 {@code LLMProvider} → Spring AI → LangChain4j。
 * 若同时存在多个用户自定义实现，需要标记 {@code @Primary}，否则启动失败并给出明确提示。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zerovector", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ZeroVectorProperties.class)
public class ZeroVectorAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ZeroVectorAutoConfiguration.class);
    private static final String LLM_BEAN_NAME = "zeroVectorLLMProvider";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.ai.chat.model.ChatModel")
    static class SpringAiConfiguration {

        @Bean("springAiLLMProvider")
        @Lazy
        LLMProvider springAiLLMProvider(org.springframework.ai.chat.model.ChatModel chatModel,
                                        ZeroVectorProperties config) {
            return new SpringAiLLMProvider(chatModel, config.getModel());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "dev.langchain4j.model.chat.ChatModel")
    static class LangChain4jConfiguration {

        @Bean("langChain4jLLMProvider")
        @Lazy
        LLMProvider langChain4jLLMProvider(dev.langchain4j.model.chat.ChatModel chatModel) {
            return new LangChain4jLLMProvider(chatModel);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CachedLLMConfiguration {

        @Bean(LLM_BEAN_NAME)
        @ConditionalOnMissingBean(name = LLM_BEAN_NAME)
        LLMProvider zeroVectorLLMProvider(ConfigurableListableBeanFactory beanFactory, ZeroVectorProperties config) {
            LLMProvider provider = resolveDelegate(beanFactory);
            if (config.getRateLimit().isEnabled()) {
                ZeroVectorProperties.RateLimit rateLimit = config.getRateLimit();
                logger.info("LLM 限流已启用: {} 次 / {} 秒",
                        rateLimit.getMaxRequests(), rateLimit.getWindowSeconds());
                provider = new RateLimitedLLMProvider(provider,
                        rateLimit.getMaxRequests(), rateLimit.getWindowSeconds());
            }
            return createCachedLLMProvider(provider, config);
        }

        /**
         * 从容器中挑选一个原始 LLMProvider。
         * <p>不能直接按类型注入：当前正在创建的 {@code zeroVectorLLMProvider} 本身也是
         * LLMProvider，会造成自引用循环。
         */
        private static LLMProvider resolveDelegate(ConfigurableListableBeanFactory beanFactory) {
            List<String> names = java.util.Arrays.stream(beanFactory.getBeanNamesForType(LLMProvider.class))
                    .filter(name -> !LLM_BEAN_NAME.equals(name))
                    .toList();

            // 优先使用用户自定义的 LLMProvider（排除本自动配置提供的适配器）
            List<String> external = names.stream()
                    .filter(name -> !"springAiLLMProvider".equals(name)
                            && !"langChain4jLLMProvider".equals(name))
                    .toList();
            if (!external.isEmpty()) {
                return selectSingle(beanFactory, external);
            }

            if (hasBean(beanFactory, "org.springframework.ai.chat.model.ChatModel")
                    && names.contains("springAiLLMProvider")) {
                return beanFactory.getBean("springAiLLMProvider", LLMProvider.class);
            }
            if (hasBean(beanFactory, "dev.langchain4j.model.chat.ChatModel")
                    && names.contains("langChain4jLLMProvider")) {
                return beanFactory.getBean("langChain4jLLMProvider", LLMProvider.class);
            }
            throw new IllegalStateException(
                    "ZeroVector 需要至少一个 LLMProvider：请配置 Spring AI / LangChain4j，或自行提供 LLMProvider Bean");
        }

        private static LLMProvider selectSingle(ConfigurableListableBeanFactory beanFactory, List<String> names) {
            if (names.size() == 1) {
                return beanFactory.getBean(names.get(0), LLMProvider.class);
            }
            List<String> primary = names.stream()
                    .filter(name -> beanFactory.getBeanDefinition(name).isPrimary())
                    .toList();
            if (primary.size() != 1) {
                throw new IllegalStateException("存在多个 LLMProvider Bean: " + names
                        + "；请将其中一个标记为 @Primary，或排除多余的模型 starter");
            }
            return beanFactory.getBean(primary.get(0), LLMProvider.class);
        }

        private static boolean hasBean(ConfigurableListableBeanFactory beanFactory, String className) {
            try {
                Class<?> type = Class.forName(className, false,
                        ZeroVectorAutoConfiguration.class.getClassLoader());
                return beanFactory.getBeanNamesForType(type).length > 0;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
    }

    private static LLMProvider createCachedLLMProvider(LLMProvider delegate, ZeroVectorProperties config) {
        if (!config.getCache().isEnabled()) {
            return delegate;
        }

        ZeroVectorProperties.SmartCacheStrategyConfig smart = config.getCache().getSmartCache();
        SimilarityConfig similarityConfig = new SimilarityConfig(
                smart.getSimilarityThreshold(),
                smart.getMaxSimilarityLength(),
                smart.isEnableSimilarityMatching());

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

        return new CachedLLMProvider(delegate, cacheConfigs, similarityConfig);
    }

    private static CacheConfig toCacheConfig(ZeroVectorProperties.CacheConfig config) {
        return new CacheConfig(config.getMaxSize(), config.getExpireAfterAccess(), config.getTimeUnit(), true);
    }

    @Bean
    @ConditionalOnMissingBean
    HookRegistry hookRegistry() {
        return new HookRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    HookExecutor hookExecutor(HookRegistry hookRegistry, ObjectProvider<LifecycleHook> hooksProvider,
                              ZeroVectorProperties properties) {
        ZeroVectorProperties.Hook hookConfig = properties.getHook();
        if (hookConfig.isEnabled()) {
            hooksProvider.forEach(hookRegistry::register);
            List<String> hooks = hookConfig.getHooks();
            if (hooks != null) {
                for (String hookClassName : hooks) {
                    registerHook(hookRegistry, hookClassName);
                }
            }
        }
        return new DefaultHookExecutor(hookRegistry);
    }

    private static void registerHook(HookRegistry registry, String hookClassName) {
        try {
            Class<?> hookClass = Class.forName(hookClassName);
            if (!LifecycleHook.class.isAssignableFrom(hookClass)) {
                logger.warn("类 {} 不是 LifecycleHook 的实现类，跳过", hookClassName);
                return;
            }
            registry.register((LifecycleHook) hookClass.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            logger.warn("无法实例化钩子类 {}: {}", hookClassName, e.getMessage());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    DocumentComprehender documentComprehender(
            @Qualifier(LLM_BEAN_NAME) LLMProvider llmProvider,
            ZeroVectorProperties properties, HookExecutor hookExecutor) {
        return new DocumentComprehender(llmProvider,
                properties.getLlmContext().getMaxChunkChars(), hookExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    KnowledgeBaseManager knowledgeBaseManager(
            @Qualifier(LLM_BEAN_NAME) LLMProvider llmProvider,
            DocumentComprehender documentComprehender,
            ZeroVectorProperties properties, HookExecutor hookExecutor) {
        KnowledgeBaseManager manager = new KnowledgeBaseManager(
                llmProvider,
                documentComprehender,
                properties.getConcurrency(),
                properties.getStorageBasePath(),
                properties.getShardSize(),
                properties.getLlmContext().getChapterSplitThresholdChars(),
                hookExecutor);
        manager.initialize();
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    SemanticHub semanticHub(KnowledgeBaseManager knowledgeBaseManager) {
        return new SemanticHub(knowledgeBaseManager);
    }
}
