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

import java.util.concurrent.atomic.AtomicLong;

public class CacheStatistics {
    private final String cacheName;
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong loadSuccessCount = new AtomicLong(0);
    private final AtomicLong loadFailureCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);
    private final AtomicLong totalLoadTime = new AtomicLong(0);
    
    public CacheStatistics(String cacheName) {
        this.cacheName = cacheName;
    }
    
    public void recordHit() {
        hitCount.incrementAndGet();
    }
    
    public void recordMiss() {
        missCount.incrementAndGet();
    }
    
    public void recordLoadSuccess(long loadTimeNanos) {
        loadSuccessCount.incrementAndGet();
        totalLoadTime.addAndGet(loadTimeNanos);
    }
    
    public void recordLoadFailure() {
        loadFailureCount.incrementAndGet();
    }
    
    public void recordEviction() {
        evictionCount.incrementAndGet();
    }
    
    public long hitCount() {
        return hitCount.get();
    }
    
    public long missCount() {
        return missCount.get();
    }
    
    public long requestCount() {
        return hitCount.get() + missCount.get();
    }
    
    public double hitRate() {
        long requests = requestCount();
        return requests == 0 ? 0.0 : (double) hitCount.get() / requests;
    }
    
    public long loadSuccessCount() {
        return loadSuccessCount.get();
    }
    
    public long loadFailureCount() {
        return loadFailureCount.get();
    }
    
    public long evictionCount() {
        return evictionCount.get();
    }
    
    public double averageLoadPenalty() {
        long loads = loadSuccessCount.get();
        return loads == 0 ? 0.0 : (double) totalLoadTime.get() / loads / 1_000_000.0;
    }
    
    public String cacheName() {
        return cacheName;
    }
    
    public void reset() {
        hitCount.set(0);
        missCount.set(0);
        loadSuccessCount.set(0);
        loadFailureCount.set(0);
        evictionCount.set(0);
        totalLoadTime.set(0);
    }
    
    @Override
    public String toString() {
        return String.format(
            "CacheStatistics[%s]{hits=%d, misses=%d, hitRate=%.2f%%, loads=%d, failures=%d, evictions=%d, avgLoadTime=%.2fms}",
            cacheName, hitCount.get(), missCount.get(), hitRate() * 100,
            loadSuccessCount.get(), loadFailureCount.get(), evictionCount.get(), averageLoadPenalty()
        );
    }
}
