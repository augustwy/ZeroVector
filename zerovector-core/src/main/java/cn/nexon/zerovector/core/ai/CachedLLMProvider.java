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

public class CachedLLMProvider implements LLMProvider {
    private static final Logger logger = LoggerFactory.getLogger(CachedLLMProvider.class);
    
    private final LLMProvider delegate;
    private final Map<SmartCacheStrategy.RequestType, Cache<String, String>> caches;
    private final Map<SmartCacheStrategy.RequestType, CacheStatistics> statistics;
    private final Map<SmartCacheStrategy.RequestType, CacheConfig> configs;
    private final Map<String, String> similarPromptCache;
    
    public CachedLLMProvider(LLMProvider delegate) {
        this(delegate, getDefaultConfigs());
    }
    
    public CachedLLMProvider(LLMProvider delegate, Map<SmartCacheStrategy.RequestType, CacheConfig> configs) {
        this.delegate = delegate;
        this.configs = new ConcurrentHashMap<>(configs);
        this.caches = new ConcurrentHashMap<>();
        this.statistics = new ConcurrentHashMap<>();
        this.similarPromptCache = new ConcurrentHashMap<>();
        
        initializeCaches();
    }
    
    private void initializeCaches() {
        for (SmartCacheStrategy.RequestType type : SmartCacheStrategy.RequestType.values()) {
            CacheConfig config = configs.getOrDefault(type, SmartCacheStrategy.getConfigForType(type));
            if (config.enabled()) {
                Cache<String, String> cache = Caffeine.newBuilder()
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
    
    private static Map<SmartCacheStrategy.RequestType, CacheConfig> getDefaultConfigs() {
        Map<SmartCacheStrategy.RequestType, CacheConfig> configs = new ConcurrentHashMap<>();
        for (SmartCacheStrategy.RequestType type : SmartCacheStrategy.RequestType.values()) {
            configs.put(type, SmartCacheStrategy.getConfigForType(type));
        }
        return configs;
    }
    
    private String getCachedResult(SmartCacheStrategy.RequestType type, String prompt, Function<String, String> loader) {
        Cache<String, String> cache = caches.get(type);
        if (cache == null) {
            return loader.apply(prompt);
        }
        
        String cacheKey = SmartCacheStrategy.generateCacheKey(type, prompt);
        CacheStatistics stats = statistics.get(type);
        
        String cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            if (stats != null) stats.recordHit();
            logger.debug("Cache hit for type: {}, key: {}", type, cacheKey);
            return cached;
        }
        
        if (stats != null) stats.recordMiss();
        
        String similarResult = findSimilarCachedResult(type, prompt);
        if (similarResult != null) {
            cache.put(cacheKey, similarResult);
            if (stats != null) stats.recordHit();
            logger.debug("Similar cache hit for type: {}, key: {}", type, cacheKey);
            return similarResult;
        }
        
        long startTime = System.nanoTime();
        try {
            String result = loader.apply(prompt);
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
    
    private String findSimilarCachedResult(SmartCacheStrategy.RequestType type, String prompt) {
        Cache<String, String> cache = caches.get(type);
        if (cache == null) return null;
        
        for (Map.Entry<String, String> entry : cache.asMap().entrySet()) {
            String cachedPrompt = similarPromptCache.get(entry.getKey());
            if (cachedPrompt != null && SmartCacheStrategy.isSimilarPrompt(prompt, cachedPrompt)) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    @Override
    public String comprehendChunk(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK, prompt, 
            p -> delegate.comprehendChunk(p));
    }
    
    @Override
    public String generateSummary(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.GENERATE_SUMMARY, prompt, 
            p -> delegate.generateSummary(p));
    }
    
    @Override
    public String clusterDocuments(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.CLUSTER_DOCUMENTS, prompt, 
            p -> delegate.clusterDocuments(p));
    }
    
    @Override
    public String extractKeywords(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, prompt, 
            p -> delegate.extractKeywords(p));
    }
    
    @Override
    public String extractEntities(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.EXTRACT_ENTITIES, prompt, 
            p -> delegate.extractEntities(p));
    }
    
    @Override
    public String generateExampleQuestions(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS, prompt, 
            p -> delegate.generateExampleQuestions(p));
    }
    
    @Override
    public String decideNavigation(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION, prompt, 
            p -> delegate.decideNavigation(p));
    }

    @Override
    public String extractQueryKeywords(String prompt) {
        return getCachedResult(SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, prompt, 
            p -> delegate.extractQueryKeywords(p));
    }
    
    public void clearCache() {
        caches.values().forEach(Cache::invalidateAll);
        similarPromptCache.clear();
        statistics.values().forEach(CacheStatistics::reset);
        logger.info("All caches cleared");
    }
    
    public void clearCache(SmartCacheStrategy.RequestType type) {
        Cache<String, String> cache = caches.get(type);
        if (cache != null) {
            cache.invalidateAll();
        }
        similarPromptCache.keySet().removeIf(key -> key.startsWith(type.name().toLowerCase()));
        if (statistics.containsKey(type)) {
            statistics.get(type).reset();
        }
        logger.info("Cache cleared for type: {}", type);
    }
    
    public Map<SmartCacheStrategy.RequestType, CacheStatistics> getStatistics() {
        return new ConcurrentHashMap<>(statistics);
    }
    
    public CacheStatistics getStatistics(SmartCacheStrategy.RequestType type) {
        return statistics.get(type);
    }
    
    public void warmupCache(Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts) {
        logger.info("Starting cache warmup with {} request types", warmupPrompts.size());
        
        warmupPrompts.forEach((type, prompts) -> {
            if (prompts == null || prompts.isEmpty()) return;
            
            logger.info("Warming up cache for type: {} with {} prompts", type, prompts.size());
            Cache<String, String> cache = caches.get(type);
            if (cache == null) return;
            
            int successCount = 0;
            int failureCount = 0;
            
            for (String prompt : prompts) {
                try {
                    String cacheKey = SmartCacheStrategy.generateCacheKey(type, prompt);
                    if (!cache.asMap().containsKey(cacheKey)) {
                        String result = switch (type) {
                            case COMPREHEND_CHUNK -> delegate.comprehendChunk(prompt);
                            case GENERATE_SUMMARY -> delegate.generateSummary(prompt);
                            case CLUSTER_DOCUMENTS -> delegate.clusterDocuments(prompt);
                            case EXTRACT_KEYWORDS -> delegate.extractKeywords(prompt);
                            case EXTRACT_ENTITIES -> delegate.extractEntities(prompt);
                            case GENERATE_EXAMPLE_QUESTIONS -> delegate.generateExampleQuestions(prompt);
                            case DECIDE_NAVIGATION -> delegate.decideNavigation(prompt);
                        };
                        cache.put(cacheKey, result);
                        similarPromptCache.put(cacheKey, prompt);
                        successCount++;
                    }
                } catch (Exception e) {
                    failureCount++;
                    logger.warn("Failed to warmup cache for type: {}, prompt: {}", type, prompt, e);
                }
            }
            
            logger.info("Cache warmup completed for type: {} - Success: {}, Failed: {}", 
                type, successCount, failureCount);
        });
        
        logger.info("Cache warmup completed");
    }
    
    public void updateCacheConfig(SmartCacheStrategy.RequestType type, CacheConfig config) {
        configs.put(type, config);
        if (config.enabled()) {
            Cache<String, String> newCache = Caffeine.newBuilder()
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
            similarPromptCache.keySet().removeIf(key -> key.startsWith(type.name().toLowerCase()));
        }
        logger.info("Cache config updated for type: {}", type);
    }
    
    public long getTotalCacheSize() {
        return caches.values().stream().mapToLong(Cache::estimatedSize).sum();
    }
    
    public long getCacheSize(SmartCacheStrategy.RequestType type) {
        Cache<String, String> cache = caches.get(type);
        return cache == null ? 0 : cache.estimatedSize();
    }
    
    public void logStatistics() {
        statistics.forEach((type, stats) -> {
            logger.info("{}", stats);
        });
    }
}
