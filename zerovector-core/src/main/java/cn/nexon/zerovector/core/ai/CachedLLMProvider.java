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

/**
 * 缓存LLM提供者，按 RequestType 分类缓存。
 */
public class CachedLLMProvider implements LLMProvider {
    private static final Logger logger = LoggerFactory.getLogger(CachedLLMProvider.class);

    private final LLMProvider delegate;
    private final Map<SmartCacheStrategy.RequestType, Cache<String, LLMResponse>> caches;
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
        this.similarPromptCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .<String, String>build()
                .asMap();
        initializeCaches();
    }

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

    private static Map<SmartCacheStrategy.RequestType, CacheConfig> getDefaultConfigs() {
        Map<SmartCacheStrategy.RequestType, CacheConfig> configs = new ConcurrentHashMap<>();
        for (SmartCacheStrategy.RequestType type : SmartCacheStrategy.RequestType.values()) {
            configs.put(type, SmartCacheStrategy.getConfigForType(type));
        }
        return configs;
    }

    @Override
    public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
        return getCachedResult(type, prompt, p -> delegate.chat(p, type));
    }

    private LLMResponse getCachedResult(SmartCacheStrategy.RequestType type, String prompt, java.util.function.Function<String, LLMResponse> loader) {
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

    private volatile int maxSearchItems = 100;

    public void setMaxSearchItems(int maxSearchItems) {
        if (maxSearchItems > 0) {
            this.maxSearchItems = maxSearchItems;
        }
    }

    public int getMaxSearchItems() {
        return maxSearchItems;
    }

    private LLMResponse findSimilarCachedResult(SmartCacheStrategy.RequestType type, String prompt, String cacheKey) {
        Cache<String, LLMResponse> cache = caches.get(type);
        if (cache == null) return null;

        int count = 0;
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

    public void clearCache() {
        caches.values().forEach(Cache::invalidateAll);
        similarPromptCache.clear();
        statistics.values().forEach(CacheStatistics::reset);
        logger.debug("All caches cleared");
    }

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

    public Map<SmartCacheStrategy.RequestType, CacheStatistics> getStatistics() {
        return new ConcurrentHashMap<>(statistics);
    }

    public CacheStatistics getStatistics(SmartCacheStrategy.RequestType type) {
        return statistics.get(type);
    }

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
                        LLMResponse result = delegate.chat(prompt, type);
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

    public long getTotalCacheSize() {
        return caches.values().stream().mapToLong(Cache::estimatedSize).sum();
    }

    public long getCacheSize(SmartCacheStrategy.RequestType type) {
        Cache<String, LLMResponse> cache = caches.get(type);
        return cache == null ? 0 : cache.estimatedSize();
    }

    public void logStatistics() {
        statistics.forEach((type, stats) -> {
            logger.debug("{}", stats);
        });
    }
}
