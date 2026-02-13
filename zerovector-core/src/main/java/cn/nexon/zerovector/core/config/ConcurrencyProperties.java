package cn.nexon.zerovector.core.config;

public class ConcurrencyProperties {
    
    private static final int MIN_MAX_CONCURRENT_REQUESTS = 1;
    private static final int MAX_MAX_CONCURRENT_REQUESTS = 100;
    private static final double MIN_REQUESTS_PER_SECOND = 0.1;
    private static final double MAX_REQUESTS_PER_SECOND = 100.0;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final long MIN_BATCH_DELAY_MS = 0;
    private static final long MAX_BATCH_DELAY_MS = 60000;
    
    private int maxConcurrentRequests = 5;
    private double requestsPerSecond = 2.0;
    private int batchSize = 10;
    private long batchDelayMs = 100;
    private boolean enableBatchProcessing = false;
    
    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }
    
    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        if (maxConcurrentRequests < MIN_MAX_CONCURRENT_REQUESTS || maxConcurrentRequests > MAX_MAX_CONCURRENT_REQUESTS) {
            throw new IllegalArgumentException(
                String.format("maxConcurrentRequests must be between %d and %d, got: %d", 
                    MIN_MAX_CONCURRENT_REQUESTS, MAX_MAX_CONCURRENT_REQUESTS, maxConcurrentRequests));
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
    }
    
    public double getRequestsPerSecond() {
        return requestsPerSecond;
    }
    
    public void setRequestsPerSecond(double requestsPerSecond) {
        if (requestsPerSecond < MIN_REQUESTS_PER_SECOND || requestsPerSecond > MAX_REQUESTS_PER_SECOND) {
            throw new IllegalArgumentException(
                String.format("requestsPerSecond must be between %.1f and %.1f, got: %.2f", 
                    MIN_REQUESTS_PER_SECOND, MAX_REQUESTS_PER_SECOND, requestsPerSecond));
        }
        this.requestsPerSecond = requestsPerSecond;
    }
    
    public int getBatchSize() {
        return batchSize;
    }
    
    public void setBatchSize(int batchSize) {
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                String.format("batchSize must be between %d and %d, got: %d", 
                    MIN_BATCH_SIZE, MAX_BATCH_SIZE, batchSize));
        }
        this.batchSize = batchSize;
    }
    
    public long getBatchDelayMs() {
        return batchDelayMs;
    }
    
    public void setBatchDelayMs(long batchDelayMs) {
        if (batchDelayMs < MIN_BATCH_DELAY_MS || batchDelayMs > MAX_BATCH_DELAY_MS) {
            throw new IllegalArgumentException(
                String.format("batchDelayMs must be between %d and %d, got: %d", 
                    MIN_BATCH_DELAY_MS, MAX_BATCH_DELAY_MS, batchDelayMs));
        }
        this.batchDelayMs = batchDelayMs;
    }
    
    public boolean isEnableBatchProcessing() {
        return enableBatchProcessing;
    }
    
    public void setEnableBatchProcessing(boolean enableBatchProcessing) {
        this.enableBatchProcessing = enableBatchProcessing;
    }
}