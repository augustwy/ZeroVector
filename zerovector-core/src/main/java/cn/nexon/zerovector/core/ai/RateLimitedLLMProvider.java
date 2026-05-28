package cn.nexon.zerovector.core.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

/**
 * 滑动窗口限流 LLM 提供者。
 * 在全局范围内限制 LLM API 调用频率，超过限制时自动等待。
 */
public class RateLimitedLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitedLLMProvider.class);

    private final LLMProvider delegate;
    private final int maxRequests;
    private final long windowMs;
    private final LinkedList<Long> timestamps = new LinkedList<>();

    public RateLimitedLLMProvider(LLMProvider delegate, int maxRequests, int windowSeconds) {
        this.delegate = delegate;
        this.maxRequests = maxRequests;
        this.windowMs = windowSeconds * 1000L;
    }

    @Override
    public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
        acquire();
        return delegate.chat(prompt, type);
    }

    private void acquire() {
        long waitMs;
        while ((waitMs = tryAcquire()) > 0) {
            logger.debug("LLM 限流等待 {}ms ({}次/{}s)", waitMs, maxRequests, windowMs / 1000);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized long tryAcquire() {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;
        while (!timestamps.isEmpty() && timestamps.getFirst() < cutoff) {
            timestamps.removeFirst();
        }
        if (timestamps.size() >= maxRequests) {
            return timestamps.getFirst() + windowMs - now;
        }
        timestamps.addLast(now);
        return 0;
    }
}
