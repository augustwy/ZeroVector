package cn.nexon.zerovector.core.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 使用统计数据
 * 用于收集和汇总 LLM 调用的 token 消耗和耗时信息
 */
public class LLMUsageStats {
    private final AtomicInteger totalInputTokens = new AtomicInteger(0);
    private final AtomicInteger totalOutputTokens = new AtomicInteger(0);
    private final AtomicInteger totalTokens = new AtomicInteger(0);
    private final AtomicLong totalDuration = new AtomicLong(0);
    private final AtomicInteger callCount = new AtomicInteger(0);

    public LLMUsageStats() {
    }

    /**
     * 添加一次 LLM 调用的统计数据
     *
     * @param response LLM 响应对象
     */
    public void add(LLMResponse response) {
        if (response == null) {
            return;
        }
        totalInputTokens.addAndGet(response.inputTokens());
        totalOutputTokens.addAndGet(response.outputTokens());
        totalTokens.addAndGet(response.totalTokens());
        totalDuration.addAndGet(response.duration());
        callCount.incrementAndGet();
    }

    /**
     * 合并另一个统计数据对象
     *
     * @param other 另一个统计数据对象
     */
    public void merge(LLMUsageStats other) {
        if (other == null) {
            return;
        }
        totalInputTokens.addAndGet(other.getTotalInputTokens());
        totalOutputTokens.addAndGet(other.getTotalOutputTokens());
        totalTokens.addAndGet(other.getTotalTokens());
        totalDuration.addAndGet(other.getTotalDuration());
        callCount.addAndGet(other.getCallCount());
    }

    public int getTotalInputTokens() {
        return totalInputTokens.get();
    }

    public int getTotalOutputTokens() {
        return totalOutputTokens.get();
    }

    public int getTotalTokens() {
        return totalTokens.get();
    }

    public long getTotalDuration() {
        return totalDuration.get();
    }

    public int getCallCount() {
        return callCount.get();
    }

    /**
     * 获取平均耗时（毫秒）
     */
    public double getAverageDuration() {
        int count = callCount.get();
        return count > 0 ? (double) totalDuration.get() / count : 0;
    }

    /**
     * 重置统计数据
     */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalTokens.set(0);
        totalDuration.set(0);
        callCount.set(0);
    }

    /**
     * 创建当前统计数据的快照
     */
    public LLMUsageStats snapshot() {
        LLMUsageStats stats = new LLMUsageStats();
        stats.totalInputTokens.set(this.totalInputTokens.get());
        stats.totalOutputTokens.set(this.totalOutputTokens.get());
        stats.totalTokens.set(this.totalTokens.get());
        stats.totalDuration.set(this.totalDuration.get());
        stats.callCount.set(this.callCount.get());
        return stats;
    }

    @Override
    public String toString() {
        return String.format(
            "LLMUsageStats{calls=%d, inputTokens=%d, outputTokens=%d, totalTokens=%d, totalDuration=%dms, avgDuration=%.2fms}",
            callCount.get(),
            totalInputTokens.get(),
            totalOutputTokens.get(),
            totalTokens.get(),
            totalDuration.get(),
            getAverageDuration()
        );
    }
}
