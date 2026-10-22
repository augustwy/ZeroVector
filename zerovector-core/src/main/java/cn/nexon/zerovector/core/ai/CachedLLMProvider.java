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

package cn.nexon.zerovector.core.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带缓存的 LLM 提供者：按 {@link SmartCacheStrategy.RequestType} 分桶缓存。
 *
 * <p>缓存值同时保存原始 prompt，避免“响应还在缓存、prompt 已被单独缓存淘汰”
 * 导致相似匹配失效。只缓存成功响应；失败调用下次会重新请求。
 */
public class CachedLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(CachedLLMProvider.class);

    private static final Set<SmartCacheStrategy.RequestType> SIMILARITY_ENABLED_TYPES = Set.of(
            SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS,
            SmartCacheStrategy.RequestType.EXTRACT_ENTITIES,
            SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS,
            SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS
    );

    private final LLMProvider delegate;
    private final Map<SmartCacheStrategy.RequestType, Cache<String, CachedEntry>> caches;
    private final Map<SmartCacheStrategy.RequestType, CacheStatistics> statistics;
    private final Map<SmartCacheStrategy.RequestType, CacheConfig> configs;
    private final SimilarityConfig similarityConfig;

    private volatile int maxSearchItems = 100;

    private record CachedEntry(LLMResponse response, String prompt) {
    }

    public CachedLLMProvider(LLMProvider delegate) {
        this(delegate, getDefaultConfigs(), SimilarityConfig.DEFAULT);
    }

    public CachedLLMProvider(LLMProvider delegate, Map<SmartCacheStrategy.RequestType, CacheConfig> configs) {
        this(delegate, configs, SimilarityConfig.DEFAULT);
    }

    public CachedLLMProvider(LLMProvider delegate, Map<SmartCacheStrategy.RequestType, CacheConfig> configs,
                             SimilarityConfig similarityConfig) {
        this.delegate = delegate;
        this.configs = new ConcurrentHashMap<>(configs);
        this.similarityConfig = similarityConfig != null ? similarityConfig : SimilarityConfig.DEFAULT;
        this.caches = new ConcurrentHashMap<>();
        this.statistics = new ConcurrentHashMap<>();
        initializeCaches();
    }

    private void initializeCaches() {
        for (SmartCacheStrategy.RequestType type : SmartCacheStrategy.RequestType.values()) {
            CacheConfig config = configs.getOrDefault(type, SmartCacheStrategy.getConfigForType(type));
            if (config.enabled()) {
                caches.put(type, buildCache(type, config));
                statistics.put(type, new CacheStatistics(type.name()));
            }
        }
    }

    private Cache<String, CachedEntry> buildCache(SmartCacheStrategy.RequestType type, CacheConfig config) {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(1, config.maxSize()))
                .expireAfterAccess(config.expireAfterAccess(), config.timeUnit())
                .removalListener((key, value, cause) -> {
                    CacheStatistics stats = statistics.get(type);
                    if (stats != null) {
                        stats.recordEviction();
                    }
                })
                .build();
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
        Cache<String, CachedEntry> cache = caches.get(type);
        if (cache == null) {
            return delegate.chat(prompt, type);
        }

        String cacheKey = SmartCacheStrategy.generateCacheKey(type, prompt);
        CacheStatistics stats = statistics.get(type);

        CachedEntry cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            if (stats != null) stats.recordHit();
            return cached.response();
        }

        if (stats != null) stats.recordMiss();

        CachedEntry similar = findSimilarCachedEntry(type, prompt, cache);
        if (similar != null) {
            CachedEntry entry = new CachedEntry(similar.response(), prompt);
            cache.put(cacheKey, entry);
            if (stats != null) stats.recordHit();
            logger.debug("相似提示词命中缓存: type={}, key={}", type, cacheKey);
            return entry.response();
        }

        long startTime = System.nanoTime();
        try {
            // Caffeine 保证同一个 key 的加载只执行一次，避免并发请求击穿缓存
            CachedEntry loaded = cache.get(cacheKey,
                    key -> new CachedEntry(delegate.chat(prompt, type), prompt));
            if (loaded == null || loaded.response() == null || !loaded.response().success()) {
                cache.invalidate(cacheKey);
                if (stats != null) stats.recordLoadFailure();
                return loaded != null && loaded.response() != null
                        ? loaded.response()
                        : LLMResponse.failure("null response");
            }
            if (stats != null) stats.recordLoadSuccess(System.nanoTime() - startTime);
            return loaded.response();
        } catch (RuntimeException e) {
            if (stats != null) stats.recordLoadFailure();
            logger.error("LLM 调用失败: type={}, key={}", type, cacheKey, e);
            throw e;
        }
    }

    private CachedEntry findSimilarCachedEntry(SmartCacheStrategy.RequestType type, String prompt,
                                               Cache<String, CachedEntry> cache) {
        if (!SIMILARITY_ENABLED_TYPES.contains(type) || !similarityConfig.enableSimilarityMatching()) {
            return null;
        }
        int count = 0;
        for (CachedEntry entry : cache.asMap().values()) {
            if (count++ >= maxSearchItems) {
                break;
            }
            if (SmartCacheStrategy.isSimilarPrompt(prompt, entry.prompt(), similarityConfig)) {
                return entry;
            }
        }
        return null;
    }

    public void setMaxSearchItems(int maxSearchItems) {
        if (maxSearchItems > 0) {
            this.maxSearchItems = maxSearchItems;
        }
    }

    public int getMaxSearchItems() {
        return maxSearchItems;
    }

    public void clearCache() {
        caches.values().forEach(Cache::invalidateAll);
        statistics.values().forEach(CacheStatistics::reset);
        logger.debug("LLM 缓存已全部清空");
    }

    public void clearCache(SmartCacheStrategy.RequestType type) {
        Cache<String, CachedEntry> cache = caches.get(type);
        if (cache != null) {
            cache.invalidateAll();
        }
        CacheStatistics stats = statistics.get(type);
        if (stats != null) {
            stats.reset();
        }
    }

    public Map<SmartCacheStrategy.RequestType, CacheStatistics> getStatistics() {
        return Map.copyOf(statistics);
    }

    public CacheStatistics getStatistics(SmartCacheStrategy.RequestType type) {
        return statistics.get(type);
    }

    public void warmupCache(Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts) {
        warmupPrompts.forEach((type, prompts) -> {
            Cache<String, CachedEntry> cache = caches.get(type);
            if (cache == null || prompts == null) {
                return;
            }
            for (String prompt : prompts) {
                try {
                    LLMResponse response = delegate.chat(prompt, type);
                    if (response != null && response.success()) {
                        cache.put(SmartCacheStrategy.generateCacheKey(type, prompt),
                                new CachedEntry(response, prompt));
                    }
                } catch (RuntimeException e) {
                    logger.warn("缓存预热失败: type={}, prompt={}", type, prompt, e);
                }
            }
        });
    }

    public void updateCacheConfig(SmartCacheStrategy.RequestType type, CacheConfig config) {
        configs.put(type, config);
        if (config.enabled()) {
            caches.put(type, buildCache(type, config));
            statistics.computeIfAbsent(type, key -> new CacheStatistics(key.name())).reset();
        } else {
            caches.remove(type);
        }
    }

    public long getTotalCacheSize() {
        return caches.values().stream().mapToLong(Cache::estimatedSize).sum();
    }

    public long getCacheSize(SmartCacheStrategy.RequestType type) {
        Cache<String, CachedEntry> cache = caches.get(type);
        return cache == null ? 0 : cache.estimatedSize();
    }

    public void logStatistics() {
        statistics.forEach((type, stats) -> logger.debug("{}", stats));
    }
}
