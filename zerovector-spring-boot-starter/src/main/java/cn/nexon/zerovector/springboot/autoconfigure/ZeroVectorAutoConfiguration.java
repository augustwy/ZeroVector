package cn.nexon.zerovector.springboot.autoconfigure;

import cn.nexon.zerovector.core.KnowledgeBaseManager;
import cn.nexon.zerovector.core.ai.CacheConfig;
import cn.nexon.zerovector.core.ai.CachedLLMProvider;
import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.springboot.service.impl.SpringAiLLMProvider;
import cn.nexon.zerovector.springboot.service.impl.LangChain4jLLMProvider;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.springboot.service.SemanticFacade;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
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
    @ConditionalOnClass(ChatModel.class)
    static class SpringAiConfiguration {
        
        @Bean
        @ConditionalOnMissingBean(name = "springAiLLMService")
        public LLMProvider springAiLLMService(ChatModel chatModel, ZeroVectorProperties config) {
            return new SpringAiLLMProvider(chatModel, config.getModel());
        }
        
        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        public LLMProvider cachedLLMProvider(LLMProvider delegate, ZeroVectorProperties config) {
            if (!config.getCache().isEnabled()) {
                logger.info("LLM缓存已禁用");
                return delegate;
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
            
            logger.info("LLM缓存已启用，配置: {}", config.getCache());
            return new CachedLLMProvider(delegate, cacheConfigs);
        }
        
        private CacheConfig toCacheConfig(ZeroVectorProperties.CacheConfig config) {
            TimeUnit timeUnit = TimeUnit.valueOf(config.getTimeUnit().toUpperCase());
            return new CacheConfig(config.getMaxSize(), config.getExpireAfterAccess(), timeUnit, true, "");
        }
    }
    
    // 检测到 Langchain4j 类路径
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ChatLanguageModel.class)
    static class Langchain4jConfiguration {
        
        @Bean
        @ConditionalOnMissingBean(name = "langChain4jLLMService")
        public LLMProvider langChain4jLLMService(ChatLanguageModel chatLanguageModel, ZeroVectorProperties config) {
            return new LangChain4jLLMProvider(chatLanguageModel, config.getLangChain4j());
        }
        
        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        public LLMProvider cachedLLMProvider(LLMProvider delegate, ZeroVectorProperties config) {
            if (!config.getCache().isEnabled()) {
                logger.info("LLM缓存已禁用");
                return delegate;
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
            
            logger.info("LLM缓存已启用，配置: {}", config.getCache());
            return new CachedLLMProvider(delegate, cacheConfigs);
        }
        
        private CacheConfig toCacheConfig(ZeroVectorProperties.CacheConfig config) {
            TimeUnit timeUnit = TimeUnit.valueOf(config.getTimeUnit().toUpperCase());
            return new CacheConfig(config.getMaxSize(), config.getExpireAfterAccess(), timeUnit, true, "");
        }
    }

    /**
     * 创建文档理解器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zerovector", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DocumentComprehender documentComprehender(LLMProvider llmProvider, ZeroVectorProperties properties) {
        ZeroVectorProperties.LLMContext llmContext = properties.getLlmContext();
        return new DocumentComprehender(llmProvider, llmContext.getMaxChunkTokens());
    }

    /**
     * 创建知识库管理器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zerovector", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KnowledgeBaseManager knowledgeBaseManager(LLMProvider llmProvider, DocumentComprehender documentComprehender, 
                                                      ZeroVectorProperties properties) {
        logger.info("创建知识库管理器，基础存储路径: {}", properties.getStorageBasePath());
        
        KnowledgeBaseManager manager = new KnowledgeBaseManager(
            llmProvider, 
            documentComprehender, 
            properties.getConcurrency(), 
            properties.getStorageBasePath()
        );
        
        try {
            manager.initialize();
            logger.info("知识库管理器初始化完成");
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
    public SemanticFacade semanticFacade(KnowledgeBaseManager knowledgeBaseManager) {
        return new SemanticFacade(knowledgeBaseManager);
    }
}