package cn.nexon.zerovector.springboot.autoconfigure;

import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;

@ConfigurationProperties(prefix = "zerovector")
@Validated
public class ZeroVectorProperties {
    
    private static final int MIN_SHARD_SIZE = 10;
    private static final int MAX_SHARD_SIZE = 10000;
    
    private boolean enabled = true;
    
    @NotBlank
    private String storageBasePath = "./data/knowledge_bases";
    
    public String getStorageBasePath() {
        return storageBasePath;
    }
    
    public void setStorageBasePath(String storageBasePath) {
        this.storageBasePath = storageBasePath;
    }
    
    private boolean useShardedStorage = true;
    
    private int shardSize = 100;
    
    private SpringAi springAi = new SpringAi();
    
    private LangChain4j langChain4j = new LangChain4j();

    private Model model = new Model("gpt-4o-mini", "gpt-4o", 0.7, -1);

    @Valid
    private ConcurrencyProperties concurrency = new ConcurrencyProperties();

    @Valid
    private LLMContext llmContext = new LLMContext();

    @Valid
    private Cache cache = new Cache();

    @Valid
    private Hook hook = new Hook();

    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isUseShardedStorage() {
        return useShardedStorage;
    }
    
    public void setUseShardedStorage(boolean useShardedStorage) {
        this.useShardedStorage = useShardedStorage;
    }
    
    public int getShardSize() {
        return shardSize;
    }
    
    public void setShardSize(int shardSize) {
        if (shardSize < MIN_SHARD_SIZE || shardSize > MAX_SHARD_SIZE) {
            throw new IllegalArgumentException(
                String.format("shardSize must be between %d and %d, got: %d", 
                    MIN_SHARD_SIZE, MAX_SHARD_SIZE, shardSize));
        }
        this.shardSize = shardSize;
    }
    
    public SpringAi getSpringAi() {
        return springAi;
    }
    
    public void setSpringAi(SpringAi springAi) {
        this.springAi = springAi;
    }
    
    public LangChain4j getLangChain4j() {
        return langChain4j;
    }
    
    public void setLangChain4j(LangChain4j langChain4j) {
        this.langChain4j = langChain4j;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public ConcurrencyProperties getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(ConcurrencyProperties concurrency) {
        this.concurrency = concurrency;
    }

    public LLMContext getLlmContext() {
        return llmContext;
    }

    public void setLlmContext(LLMContext llmContext) {
        this.llmContext = llmContext;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Hook getHook() {
        return hook;
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    /**
     * 模型配置
     */
    public record Model(String clustering, String navigation, double temperature, int maxTokens) {

    }

    /**
     * Spring AI配置
     */
    public static class SpringAi {

    }
    
    /**
     * LangChain4j配置
     */
    public static class LangChain4j {

    }

    /**
     * LLM上下文配置
     */
    public static class LLMContext {
        private int maxChunkTokens = 4000;
        private int chunkOverlapTokens = 200;

        public int getMaxChunkTokens() {
            return maxChunkTokens;
        }

        public void setMaxChunkTokens(int maxChunkTokens) {
            this.maxChunkTokens = maxChunkTokens;
        }

        public int getChunkOverlapTokens() {
            return chunkOverlapTokens;
        }

        public void setChunkOverlapTokens(int chunkOverlapTokens) {
            this.chunkOverlapTokens = chunkOverlapTokens;
        }
    }

    /**
     * 缓存配置
     */
    public static class Cache {
        private boolean enabled = true;
        
        private CacheConfig comprehendChunk = new CacheConfig(5000L, 4L, "HOURS");
        private CacheConfig generateSummary = new CacheConfig(2000L, 2L, "HOURS");
        private CacheConfig clusterDocuments = new CacheConfig(1000L, 6L, "HOURS");
        private CacheConfig extractKeywords = new CacheConfig(3000L, 8L, "HOURS");
        private CacheConfig extractEntities = new CacheConfig(3000L, 8L, "HOURS");
        private CacheConfig generateExampleQuestions = new CacheConfig(2000L, 12L, "HOURS");
        private CacheConfig decideNavigation = new CacheConfig(5000L, 1L, "HOURS");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public CacheConfig getComprehendChunk() {
            return comprehendChunk;
        }

        public void setComprehendChunk(CacheConfig comprehendChunk) {
            this.comprehendChunk = comprehendChunk;
        }

        public CacheConfig getGenerateSummary() {
            return generateSummary;
        }

        public void setGenerateSummary(CacheConfig generateSummary) {
            this.generateSummary = generateSummary;
        }

        public CacheConfig getClusterDocuments() {
            return clusterDocuments;
        }

        public void setClusterDocuments(CacheConfig clusterDocuments) {
            this.clusterDocuments = clusterDocuments;
        }

        public CacheConfig getExtractKeywords() {
            return extractKeywords;
        }

        public void setExtractKeywords(CacheConfig extractKeywords) {
            this.extractKeywords = extractKeywords;
        }

        public CacheConfig getExtractEntities() {
            return extractEntities;
        }

        public void setExtractEntities(CacheConfig extractEntities) {
            this.extractEntities = extractEntities;
        }

        public CacheConfig getGenerateExampleQuestions() {
            return generateExampleQuestions;
        }

        public void setGenerateExampleQuestions(CacheConfig generateExampleQuestions) {
            this.generateExampleQuestions = generateExampleQuestions;
        }

        public CacheConfig getDecideNavigation() {
            return decideNavigation;
        }

        public void setDecideNavigation(CacheConfig decideNavigation) {
            this.decideNavigation = decideNavigation;
        }
    }

    /**
     * 缓存配置项
     */
    public static class CacheConfig {
        private long maxSize;
        private long expireAfterAccess;
        private String timeUnit;

        public CacheConfig() {
        }

        public CacheConfig(long maxSize, long expireAfterAccess, String timeUnit) {
            this.maxSize = maxSize;
            this.expireAfterAccess = expireAfterAccess;
            this.timeUnit = timeUnit;
        }

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public long getExpireAfterAccess() {
            return expireAfterAccess;
        }

        public void setExpireAfterAccess(long expireAfterAccess) {
            this.expireAfterAccess = expireAfterAccess;
        }

        public String getTimeUnit() {
            return timeUnit;
        }

        public void setTimeUnit(String timeUnit) {
            this.timeUnit = timeUnit;
        }
    }

    /**
     * 钩子配置
     */
    public static class Hook {
        private boolean enabled = true;
        private List<String> hooks = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getHooks() {
            return hooks;
        }

        public void setHooks(List<String> hooks) {
            this.hooks = hooks;
        }
    }
}