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

package cn.nexon.zerovector.core.config;

/**
 * 并发与导航参数。
 *
 * <p>LLM 的全局速率限制由 {@code RateLimitedLLMProvider} 负责，这里只控制
 * 构建阶段的并行度与单次导航的最大步数，避免两套限流叠加。
 */
public class ConcurrencyProperties {

    public static final int DEFAULT_SHARD_SIZE = 100;
    private static final int MIN_MAX_CONCURRENT_REQUESTS = 1;
    private static final int MAX_MAX_CONCURRENT_REQUESTS = 100;

    private int maxConcurrentRequests = 5;
    private int maxNavigationSteps = 20;

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        if (maxConcurrentRequests < MIN_MAX_CONCURRENT_REQUESTS
                || maxConcurrentRequests > MAX_MAX_CONCURRENT_REQUESTS) {
            throw new IllegalArgumentException(
                    String.format("maxConcurrentRequests must be between %d and %d, got: %d",
                            MIN_MAX_CONCURRENT_REQUESTS, MAX_MAX_CONCURRENT_REQUESTS, maxConcurrentRequests));
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public int getMaxNavigationSteps() {
        return maxNavigationSteps;
    }

    public void setMaxNavigationSteps(int maxNavigationSteps) {
        if (maxNavigationSteps < 5 || maxNavigationSteps > 100) {
            throw new IllegalArgumentException(
                    String.format("maxNavigationSteps must be between 5 and 100, got: %d", maxNavigationSteps));
        }
        this.maxNavigationSteps = maxNavigationSteps;
    }
}
