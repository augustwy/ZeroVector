package cn.nexon.zerovector.core.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存LLM提供者
 * 为LLMProvider添加缓存功能，提高响应速度和减少API调用
 */
public class CachedLLMProvider implements LLMProvider {
    private static final Logger logger = LoggerFactory.getLogger(CachedLLMProvider.class);
    
    /** 委托的LLM提供者 */
    private final LLMProvider delegate;
    /** 按请求类型分类的缓存 */
    private final Map<SmartCacheStrategy.RequestType, Cache<String, LLMResponse>> caches;
    /** 缓存统计信息 */
    private final Map<SmartCacheStrategy.RequestType, CacheStatistics> statistics;
    /** 缓存配置 */
    private final Map<SmartCacheStrategy.RequestType, CacheConfig> configs;
    /** 相似提示词缓存，用于相似性匹配（大小受限，自动淘汰） */
    private final Map<String, String> similarPromptCache;
    /** 请求类型到委托方法的调度表 */
    private final Map<SmartCacheStrategy.RequestType, Function<String, LLMResponse>> dispatcher;

    /**
     * 创建缓存LLM提供者
     * @param delegate 委托的LLM提供者
     */
    public CachedLLMProvider(LLMProvider delegate) {
        this(delegate, getDefaultConfigs());
    }

    /**
     * 创建缓存LLM提供者
     * @param delegate 委托的LLM提供者
     * @param configs 缓存配置
     */
    public CachedLLMProvider(LLMProvider delegate, Map<SmartCacheStrategy.RequestType, CacheConfig> configs) {
        this.delegate = delegate;
        this.configs = new ConcurrentHashMap<>(configs);
        this.caches = new ConcurrentHashMap<>();
        this.statistics = new ConcurrentHashMap<>();
        this.similarPromptCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .<String, String>build()
                .asMap();

        this.dispatcher = Map.of(
            SmartCacheStrategy.RequestType.COMPREHEND_CHUNK, delegate::comprehendChunk,
            SmartCacheStrategy.RequestType.GENERATE_SUMMARY, delegate::generateSummary,
            SmartCacheStrategy.RequestType.CLUSTER_DOCUMENTS, delegate::clusterDocuments,
            SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, delegate::extractKeywords,
            SmartCacheStrategy.RequestType.EXTRACT_ENTITIES, delegate::extractEntities,
            SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS, delegate::generateExampleQuestions,
            SmartCacheStrategy.RequestType.DECIDE_NAVIGATION, delegate::decideNavigation,
            SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS, delegate::extractQueryKeywords
        );

        initializeCaches();
    }
    
    /**
     * 初始化缓存
     */
    private void initializeCaches() {
        for (SmartCacheStrategy.RequestType type : SmartCacheStrategy.RequestType.values()) {
            CacheConfig config = configs.getOrDefault(type, SmartCacheStrategy.getConfigForType(type));
            if (config.enabled()) {
                Cache<String, LLMResponse> cache = Caffeine.newBuilder()
                    .maximumSize(config.maxSize())
                    .expireAfterAccess(config.expireAfterAccess(), config.timeUnit())
                    .recordStats()
                    .removalListener((key, value, cause) -> {
                        if (statistics.containsKey(type)) {
                            statistics.get(type).recordEviction();
                        }
                    })
                    .build();
                caches.put(type, cache);
                statistics.put(type, new CacheStatistics(type.name()));
            }
        }
    }
    
    /**
     * 获取默认缓存配置
     * @return 默认缓存配置
     */
    private static Map<SmartCacheStrategy.RequestType, CacheConfig> getDefaultConfigs() {
        Map<SmartCacheStrategy.RequestType, CacheConfig> configs = new ConcurrentHashMap<>();
        for (SmartCacheStrategy.RequestType type : SmartCacheStrategy.RequestType.values()) {
            configs.put(type, SmartCacheStrategy.getConfigForType(type));
        }
        return configs;
    }
    
    /**
     * 获取缓存结果
     * @param type 请求类型
     * @param prompt 提示词
     * @param loader 加载器函数
     * @return LLM响应
     */
    private LLMResponse getCachedResult(SmartCacheStrategy.RequestType type, String prompt, Function<String, LLMResponse> loader) {
        Cache<String, LLMResponse> cache = caches.get(type);
        if (cache == null) {
            return loader.apply(prompt);
        }
        
        String cacheKey = SmartCacheStrategy.generateCacheKey(type, prompt);
        CacheStatistics stats = statistics.get(type);
        
        LLMResponse cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            if (stats != null) stats.recordHit();
            logger.debug("Cache hit for type: {}, key: {}", type, cacheKey);
            return cached;
        }
        
        if (stats != null) stats.recordMiss();
        
        LLMResponse similarResult = findSimilarCachedResult(type, prompt, cacheKey);
        if (similarResult != null) {
            cache.put(cacheKey, similarResult);
            if (stats != null) stats.recordHit();
            logger.debug("Similar cache hit for type: {}, key: {}", type, cacheKey);
            return similarResult;
        }
        
        long startTime = System.nanoTime();
        try {
            LLMResponse result = loader.apply(prompt);
            long loadTime = System.nanoTime() - startTime;
            
            cache.put(cacheKey, result);
            if (stats != null) stats.recordLoadSuccess(loadTime);
            
            similarPromptCache.put(cacheKey, prompt);
            
            logger.debug("Cache miss and loaded for type: {}, key: {}, loadTime: {}ms", 
                type, cacheKey, loadTime / 1_000_000.0);
            return result;
        } catch (Exception e) {
            long loadTime = System.nanoTime() - startTime;
            if (stats != null) stats.recordLoadFailure();
            logger.error("Failed to load result for type: {}, key: {}", type, cacheKey, e);
            throw e;
        }
    }
    
    private volatile int maxSearchItems = 100; // 最多检查100个最近使用的条目
    
    /**
     * 设置相似性搜索的最大条目数
     * @param maxSearchItems 最大条目数
     */
    public void setMaxSearchItems(int maxSearchItems) {
        if (maxSearchItems > 0) {
            this.maxSearchItems = maxSearchItems;
        }
    }
    
    /**
     * 获取相似性搜索的最大条目数
     * @return 最大条目数
     */
    public int getMaxSearchItems() {
        return maxSearchItems;
    }
    
    private LLMResponse findSimilarCachedResult(SmartCacheStrategy.RequestType type, String prompt, String cacheKey) {
        Cache<String, LLMResponse> cache = caches.get(type);
        if (cache == null) return null;
        
        // 限制搜索范围，只检查最近使用的条目
        int count = 0;
        
        // 使用LinkedHashMap的特性，按访问顺序遍历
        for (Map.Entry<String, LLMResponse> entry : cache.asMap().entrySet()) {
            if (count >= maxSearchItems) break;
            
            String cachedPrompt = similarPromptCache.get(entry.getKey());
            if (cachedPrompt != null && SmartCacheStrategy.isSimilarPrompt(prompt, cachedPrompt)) {
                return entry.getValue();
            }
            count++;
        }
        return null;
    }
    
    @Override
    public LLMResponse comprehendChunk(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK, prompt, delegate::comprehendChunk);
    }

    @Override
    public LLMResponse generateSummary(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.GENERATE_SUMMARY, prompt, delegate::generateSummary);
    }

    @Override
    public LLMResponse clusterDocuments(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.CLUSTER_DOCUMENTS, prompt, delegate::clusterDocuments);
    }

    @Override
    public LLMResponse extractKeywords(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, prompt, delegate::extractKeywords);
    }

    @Override
    public LLMResponse extractEntities(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.EXTRACT_ENTITIES, prompt, delegate::extractEntities);
    }

    @Override
    public LLMResponse generateExampleQuestions(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS, prompt, delegate::generateExampleQuestions);
    }

    @Override
    public LLMResponse decideNavigation(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION, prompt, delegate::decideNavigation);
    }

    @Override
    public LLMResponse extractQueryKeywords(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS, prompt, delegate::extractQueryKeywords);
    }
    
    /**
     * 清除所有缓存
     */
    public void clearCache() {
        caches.values().forEach(Cache::invalidateAll);
        similarPromptCache.clear();
        statistics.values().forEach(CacheStatistics::reset);
        logger.debug("All caches cleared");
    }
    
    /**
     * 清除指定类型的缓存
     * @param type 请求类型
     */
    public void clearCache(SmartCacheStrategy.RequestType type) {
        Cache<String, LLMResponse> cache = caches.get(type);
        if (cache != null) {
            cache.invalidateAll();
        }
        similarPromptCache.keySet().removeIf(key -> key.startsWith(type.name().toLowerCase() + ":"));
        if (statistics.containsKey(type)) {
            statistics.get(type).reset();
        }
        logger.debug("Cache cleared for type: {}", type);
    }
    
    /**
     * 获取所有缓存统计信息
     * @return 缓存统计信息
     */
    public Map<SmartCacheStrategy.RequestType, CacheStatistics> getStatistics() {
        return new ConcurrentHashMap<>(statistics);
    }
    
    /**
     * 获取指定类型的缓存统计信息
     * @param type 请求类型
     * @return 缓存统计信息
     */
    public CacheStatistics getStatistics(SmartCacheStrategy.RequestType type) {
        return statistics.get(type);
    }
    
    /**
     * 预热缓存
     * @param warmupPrompts 预热提示词
     */
    public void warmupCache(Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts) {
        logger.debug("Starting cache warmup with {} request types", warmupPrompts.size());
        
        warmupPrompts.forEach((type, prompts) -> {
            if (prompts == null || prompts.isEmpty()) return;
            
            logger.debug("Warming up cache for type: {} with {} prompts", type, prompts.size());
            Cache<String, LLMResponse> cache = caches.get(type);
            if (cache == null) return;
            
            int successCount = 0;
            int failureCount = 0;
            
            for (String prompt : prompts) {
                try {
                    String cacheKey = SmartCacheStrategy.generateCacheKey(type, prompt);
                    if (!cache.asMap().containsKey(cacheKey)) {
                        LLMResponse result = dispatcher.get(type).apply(prompt);
                        cache.put(cacheKey, result);
                        similarPromptCache.put(cacheKey, prompt);
                        successCount++;
                    }
                } catch (Exception e) {
                    failureCount++;
                    logger.warn("Failed to warmup cache for type: {}, prompt: {}", type, prompt, e);
                }
            }
            
            logger.debug("Cache warmup completed for type: {} - Success: {}, Failed: {}", 
                type, successCount, failureCount);
        });
        
        logger.debug("Cache warmup completed");
    }
    
    /**
     * 更新缓存配置
     * @param type 请求类型
     * @param config 缓存配置
     */
    public void updateCacheConfig(SmartCacheStrategy.RequestType type, CacheConfig config) {
        configs.put(type, config);
        if (config.enabled()) {
            Cache<String, LLMResponse> newCache = Caffeine.newBuilder()
                .maximumSize(config.maxSize())
                .expireAfterAccess(config.expireAfterAccess(), config.timeUnit())
                .recordStats()
                .removalListener((key, value, cause) -> {
                    if (statistics.containsKey(type)) {
                        statistics.get(type).recordEviction();
                    }
                })
                .build();
            caches.put(type, newCache);
            if (!statistics.containsKey(type)) {
                statistics.put(type, new CacheStatistics(type.name()));
            }
        } else {
            caches.remove(type);
            similarPromptCache.keySet().removeIf(key -> key.startsWith(type.name().toLowerCase() + ":"));
        }
        logger.debug("Cache config updated for type: {}", type);
    }
    
    /**
     * 获取总缓存大小
     * @return 总缓存大小
     */
    public long getTotalCacheSize() {
        return caches.values().stream().mapToLong(Cache::estimatedSize).sum();
    }
    
    /**
     * 获取指定类型的缓存大小
     * @param type 请求类型
     * @return 缓存大小
     */
    public long getCacheSize(SmartCacheStrategy.RequestType type) {
        Cache<String, LLMResponse> cache = caches.get(type);
        return cache == null ? 0 : cache.estimatedSize();
    }
    
    /**
     * 记录缓存统计信息
     */
    public void logStatistics() {
        statistics.forEach((type, stats) -> {
            logger.debug("{}", stats);
        });
    }
}
