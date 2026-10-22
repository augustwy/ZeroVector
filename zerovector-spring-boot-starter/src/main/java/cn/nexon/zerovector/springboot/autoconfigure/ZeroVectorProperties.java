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

import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ConfigurationProperties(prefix = "zerovector")
@Validated
public class ZeroVectorProperties {

    private static final int MIN_SHARD_SIZE = 10;
    private static final int MAX_SHARD_SIZE = 10000;

    private boolean enabled = true;

    @NotBlank
    private String storageBasePath = "./data/knowledge_bases";

    private int shardSize = ConcurrencyProperties.DEFAULT_SHARD_SIZE;

    private Model model = new Model("gpt-4o-mini", "gpt-4o-mini", "gpt-4o-mini", 0.7, -1);

    @Valid
    private ConcurrencyProperties concurrency = new ConcurrencyProperties();

    @Valid
    private LLMContext llmContext = new LLMContext();

    @Valid
    private Cache cache = new Cache();

    @Valid
    private Hook hook = new Hook();

    @Valid
    private RateLimit rateLimit = new RateLimit();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStorageBasePath() {
        return storageBasePath;
    }

    public void setStorageBasePath(String storageBasePath) {
        this.storageBasePath = storageBasePath;
    }

    public int getShardSize() {
        return shardSize;
    }

    public void setShardSize(int shardSize) {
        if (shardSize < MIN_SHARD_SIZE || shardSize > MAX_SHARD_SIZE) {
            throw new IllegalArgumentException(String.format(
                    "shardSize must be between %d and %d, got: %d", MIN_SHARD_SIZE, MAX_SHARD_SIZE, shardSize));
        }
        this.shardSize = shardSize;
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

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * 模型槽位。
     *
     * @param heavy 离线复杂任务：文档理解、聚类、摘要
     * @param light 离线简单任务：关键词、实体、示例问题提取
     * @param fast  在线快速任务：导航决策、查询关键词、最终问答
     */
    public record Model(String heavy, String light, String fast, double temperature, int maxTokens) {
    }

    public static class LLMContext {
        /** 单次送给 LLM 的最大字符数（不是 token 数）。 */
        private int maxChunkChars = 4000;

        /** 超过该字符数才按章节切片；小文档默认整篇作为一个检索单位。 */
        private long chapterSplitThresholdChars = 100_000L;

        public int getMaxChunkChars() {
            return maxChunkChars;
        }

        public void setMaxChunkChars(int maxChunkChars) {
            if (maxChunkChars < 200) {
                throw new IllegalArgumentException("maxChunkChars must be >= 200");
            }
            this.maxChunkChars = maxChunkChars;
        }

        public long getChapterSplitThresholdChars() {
            return chapterSplitThresholdChars;
        }

        public void setChapterSplitThresholdChars(long chapterSplitThresholdChars) {
            if (chapterSplitThresholdChars < 1_000) {
                throw new IllegalArgumentException("chapterSplitThresholdChars must be >= 1000");
            }
            this.chapterSplitThresholdChars = chapterSplitThresholdChars;
        }
    }

    public static class Cache {
        private boolean enabled = true;
        private SmartCacheStrategyConfig smartCache = new SmartCacheStrategyConfig();
        private CacheConfig comprehendChunk = new CacheConfig(5000L, 4L, TimeUnit.HOURS);
        private CacheConfig generateSummary = new CacheConfig(2000L, 2L, TimeUnit.HOURS);
        private CacheConfig clusterDocuments = new CacheConfig(1000L, 6L, TimeUnit.HOURS);
        private CacheConfig extractKeywords = new CacheConfig(3000L, 8L, TimeUnit.HOURS);
        private CacheConfig extractEntities = new CacheConfig(3000L, 8L, TimeUnit.HOURS);
        private CacheConfig generateExampleQuestions = new CacheConfig(2000L, 12L, TimeUnit.HOURS);
        private CacheConfig decideNavigation = new CacheConfig(5000L, 1L, TimeUnit.HOURS);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public SmartCacheStrategyConfig getSmartCache() {
            return smartCache;
        }

        public void setSmartCache(SmartCacheStrategyConfig smartCache) {
            this.smartCache = smartCache;
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

    public static class SmartCacheStrategyConfig {
        private double similarityThreshold = 0.85;
        private int maxSimilarityLength = 500;
        private boolean enableSimilarityMatching = true;

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public int getMaxSimilarityLength() {
            return maxSimilarityLength;
        }

        public void setMaxSimilarityLength(int maxSimilarityLength) {
            this.maxSimilarityLength = maxSimilarityLength;
        }

        public boolean isEnableSimilarityMatching() {
            return enableSimilarityMatching;
        }

        public void setEnableSimilarityMatching(boolean enableSimilarityMatching) {
            this.enableSimilarityMatching = enableSimilarityMatching;
        }
    }

    public static class CacheConfig {
        private long maxSize;
        private long expireAfterAccess;
        private TimeUnit timeUnit;

        public CacheConfig() {
        }

        public CacheConfig(long maxSize, long expireAfterAccess, TimeUnit timeUnit) {
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

        public TimeUnit getTimeUnit() {
            return timeUnit;
        }

        public void setTimeUnit(TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
        }
    }

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

    /** LLM 全局滑动窗口限流。 */
    public static class RateLimit {
        private boolean enabled = false;
        private int maxRequests = 5;
        private int windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("maxRequests must be >= 1");
            }
            this.maxRequests = maxRequests;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            if (windowSeconds < 1) {
                throw new IllegalArgumentException("windowSeconds must be >= 1");
            }
            this.windowSeconds = windowSeconds;
        }
    }
}
