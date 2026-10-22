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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

/**
 * 滑动窗口限流 LLM 提供者：在全局范围内限制 API 调用频率，超限时等待。
 */
public class RateLimitedLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitedLLMProvider.class);

    private final LLMProvider delegate;
    private final int maxRequests;
    private final long windowNanos;
    private final LinkedList<Long> timestamps = new LinkedList<>();

    public RateLimitedLLMProvider(LLMProvider delegate, int maxRequests, int windowSeconds) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("maxRequests must be >= 1");
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be >= 1");
        }
        this.delegate = delegate;
        this.maxRequests = maxRequests;
        this.windowNanos = windowSeconds * 1_000_000_000L;
    }

    @Override
    public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
        acquire();
        return delegate.chat(prompt, type);
    }

    private void acquire() {
        long waitNanos;
        while ((waitNanos = tryAcquire()) > 0) {
            logger.debug("LLM 限流等待 {}ms ({}/{}s)", waitNanos / 1_000_000,
                    maxRequests, windowNanos / 1_000_000_000L);
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized long tryAcquire() {
        long now = System.nanoTime();
        long cutoff = now - windowNanos;
        while (!timestamps.isEmpty() && timestamps.getFirst() < cutoff) {
            timestamps.removeFirst();
        }
        if (timestamps.size() >= maxRequests) {
            return Math.max(1, timestamps.getFirst() + windowNanos - now);
        }
        timestamps.addLast(now);
        return 0;
    }
}
