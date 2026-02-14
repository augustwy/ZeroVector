package cn.nexon.zerovector.springboot.autoconfigure;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.SemanticTreeManager;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.springboot.service.SemanticFacade;
import cn.nexon.zerovector.springboot.service.impl.LangChain4jLLMProvider;
import cn.nexon.zerovector.springboot.service.impl.SpringAiLLMProvider;
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
        @ConditionalOnMissingBean(LLMProvider.class)
        public LLMProvider springAiLLMService(ChatModel chatModel, ZeroVectorProperties config) {
            return new SpringAiLLMProvider(chatModel, config.getModel());
        }
    }

    // 检测到 Langchain4j 类路径
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ChatLanguageModel.class)
    static class Langchain4jConfiguration {

        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        public LLMProvider langChain4jLLMService(ChatLanguageModel chatLanguageModel, ZeroVectorProperties config) {
            return new LangChain4jLLMProvider(chatLanguageModel, config.getLangChain4j());
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
     * 创建语义树管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public SemanticTreeManager semanticTreeService(LLMProvider llmProvider, DocumentComprehender documentComprehender, ZeroVectorProperties properties) {
        logger.info("创建语义树管理器，存储路径: {}, 分片存储: {}",
                properties.getStoragePath(), properties.isUseShardedStorage());

        Path storagePath = Paths.get(properties.getStoragePath());
        SemanticTreeManager service = new SemanticTreeManager(llmProvider, documentComprehender, storagePath, properties.isUseShardedStorage(), properties.getConcurrency());
        try {
            service.initialize();
            logger.info("语义树管理器初始化完成");
            return service;
        } catch (Exception e) {
            logger.error("语义树管理器初始化失败", e);
            throw new RuntimeException("语义树管理器初始化失败", e);
        }
    }

    /**
     * 高级API
     */
    @Bean
    @ConditionalOnMissingBean
    public SemanticFacade zeroVectorService(SemanticTreeManager semanticTreeManager) {
        return new SemanticFacade(semanticTreeManager);
    }
}