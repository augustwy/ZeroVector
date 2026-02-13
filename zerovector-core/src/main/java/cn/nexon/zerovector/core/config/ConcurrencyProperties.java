package cn.nexon.zerovector.core.config;

public class ConcurrencyProperties {
    
    /**
     * 最大并发请求数量
     */
    private int maxConcurrentRequests = 5;
    
    /**
     * 每秒请求数限制（使用令牌桶算法）
     */
    private double requestsPerSecond = 2.0;
    
    /**
     * 批处理大小
     */
    private int batchSize = 10;
    
    /**
     * 批次间延迟（毫秒）
     */
    private long batchDelayMs = 100;
    
    /**
     * 是否启用批处理模式
     */
    private boolean enableBatchProcessing = false;
    
    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }
    
    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }
    
    public double getRequestsPerSecond() {
        return requestsPerSecond;
    }
    
    public void setRequestsPerSecond(double requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }
    
    public int getBatchSize() {
        return batchSize;
    }
    
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
    
    public long getBatchDelayMs() {
        return batchDelayMs;
    }
    
    public void setBatchDelayMs(long batchDelayMs) {
        this.batchDelayMs = batchDelayMs;
    }
    
    public boolean isEnableBatchProcessing() {
        return enableBatchProcessing;
    }
    
    public void setEnableBatchProcessing(boolean enableBatchProcessing) {
        this.enableBatchProcessing = enableBatchProcessing;
    }
}