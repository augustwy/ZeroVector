package cn.nexon.zerovector.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

public class PerformanceMonitor {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMonitor.class);
    
    private final String operationName;
    private long startTime;
    private long endTime;
    private boolean running;
    
    private static final Map<String, PerformanceMetrics> globalMetrics = new ConcurrentHashMap<>();
    
    public PerformanceMonitor(String operationName) {
        this.operationName = operationName;
        this.startTime = 0;
        this.endTime = 0;
        this.running = false;
    }
    
    public void start() {
        this.startTime = System.nanoTime();
        this.running = true;
    }
    
    public void stop() {
        if (this.running) {
            this.endTime = System.nanoTime();
            this.running = false;
            long duration = this.endTime - this.startTime;
            recordMetrics(operationName, duration);
        }
    }
    
    public long getDuration() {
        if (this.running) {
            return System.nanoTime() - this.startTime;
        }
        return this.endTime - this.startTime;
    }
    
    public long getDurationMillis() {
        return getDuration() / 1_000_000;
    }
    
    public static void recordMetrics(String operationName, long durationNanos) {
        globalMetrics.computeIfAbsent(operationName, k -> new PerformanceMetrics()).record(durationNanos);
    }
    
    public static PerformanceMetrics getMetrics(String operationName) {
        return globalMetrics.get(operationName);
    }
    
    public static Map<String, PerformanceMetrics> getAllMetrics() {
        return new HashMap<>(globalMetrics);
    }
    
    public static void logMetrics(String operationName) {
        PerformanceMetrics metrics = globalMetrics.get(operationName);
        if (metrics != null) {
            logger.info("性能指标 - {}: {}", operationName, metrics);
        }
    }
    
    public static void logAllMetrics() {
        if (globalMetrics.isEmpty()) {
            logger.info("没有可用的性能指标");
            return;
        }
        
        logger.info("========== 性能指标汇总 ==========");
        globalMetrics.forEach((name, metrics) -> {
            logger.info("  {}: {}", name, metrics);
        });
        logger.info("===================================");
    }
    
    public static void resetMetrics(String operationName) {
        globalMetrics.remove(operationName);
    }
    
    public static void resetAllMetrics() {
        globalMetrics.clear();
    }
    
    public static class PerformanceMetrics {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        private final AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxDuration = new AtomicLong(0);
        private final DoubleAdder sumOfSquares = new DoubleAdder();
        
        public void record(long durationNanos) {
            count.incrementAndGet();
            totalDuration.addAndGet(durationNanos);
            
            long currentMin = minDuration.get();
            while (durationNanos < currentMin && !minDuration.compareAndSet(currentMin, durationNanos)) {
                currentMin = minDuration.get();
            }
            
            long currentMax = maxDuration.get();
            while (durationNanos > currentMax && !maxDuration.compareAndSet(currentMax, durationNanos)) {
                currentMax = maxDuration.get();
            }
            
            sumOfSquares.add((double) durationNanos * durationNanos);
        }
        
        public long getCount() {
            return count.get();
        }
        
        public long getTotalDurationNanos() {
            return totalDuration.get();
        }
        
        public long getTotalDurationMillis() {
            return totalDuration.get() / 1_000_000;
        }
        
        public double getAverageDurationNanos() {
            long c = count.get();
            return c > 0 ? (double) totalDuration.get() / c : 0;
        }
        
        public double getAverageDurationMillis() {
            return getAverageDurationNanos() / 1_000_000.0;
        }
        
        public long getMinDurationNanos() {
            return minDuration.get();
        }
        
        public long getMinDurationMillis() {
            return minDuration.get() / 1_000_000;
        }
        
        public long getMaxDurationNanos() {
            return maxDuration.get();
        }
        
        public long getMaxDurationMillis() {
            return maxDuration.get() / 1_000_000;
        }
        
        public double getStandardDeviationNanos() {
            long c = count.get();
            if (c < 2) return 0;
            
            double mean = getAverageDurationNanos();
            double variance = (sumOfSquares.sum() / c) - (mean * mean);
            return Math.sqrt(Math.max(0, variance));
        }
        
        public double getStandardDeviationMillis() {
            return getStandardDeviationNanos() / 1_000_000.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "调用次数=%d, 总耗时=%dms, 平均耗时=%.2fms, 最小耗时=%dms, 最大耗时=%dms, 标准差=%.2fms",
                getCount(),
                getTotalDurationMillis(),
                getAverageDurationMillis(),
                getMinDurationMillis(),
                getMaxDurationMillis(),
                getStandardDeviationMillis()
            );
        }
    }
}
