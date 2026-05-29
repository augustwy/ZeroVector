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

public class ConcurrencyProperties {
    
    private static final int MIN_MAX_CONCURRENT_REQUESTS = 1;
    private static final int MAX_MAX_CONCURRENT_REQUESTS = 100;
    private static final double MIN_REQUESTS_PER_SECOND = 0.1;
    private static final double MAX_REQUESTS_PER_SECOND = 100.0;
    
    private int maxConcurrentRequests = 5;
    private double requestsPerSecond = 2.0;
    private int maxNavigationSteps = 20;
    
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