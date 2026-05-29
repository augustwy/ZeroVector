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

package cn.nexon.zerovector.core.util;

import cn.nexon.zerovector.core.config.ConcurrencyProperties;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 创建带限流的虚拟线程执行器，用于并行化 LLM 调用。
 */
public final class LLMExecutors {

    private LLMExecutors() {
    }

    /**
     * 根据并发配置创建执行器。
     *
     * @param props 并发配置（maxConcurrentRequests 控制 Semaphore，requestsPerSecond 控制速率）
     * @return 带限流的虚拟线程执行器
     */
    public static ExecutorService create(ConcurrencyProperties props) {
        int maxConcurrent = props.getMaxConcurrentRequests();
        double rps = props.getRequestsPerSecond();

        Semaphore semaphore = new Semaphore(maxConcurrent);
        RateLimiter rateLimiter = new RateLimiter(rps);
        ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();

        return new ExecutorService() {

            @Override
            public void execute(Runnable command) {
                delegate.execute(() -> runWithLimit(command));
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return delegate.submit(() -> runWithLimit(task));
            }

            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return delegate.submit(() -> {
                    runWithLimit(task);
                    return result;
                });
            }

            @Override
            public Future<?> submit(Runnable task) {
                return delegate.submit(() -> runWithLimit(task));
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
                return tasks.stream().map(this::submit).toList();
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                                  long timeout, TimeUnit unit) {
                // 简化实现：忽略 timeout
                return invokeAll(tasks);
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                                   long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void shutdown() {
                delegate.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return delegate.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return delegate.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return delegate.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }

            @Override
            public void close() {
                delegate.close();
            }

            private void runWithLimit(Runnable command) {
                semaphore.acquireUninterruptibly();
                try {
                    rateLimiter.acquire();
                    command.run();
                } finally {
                    semaphore.release();
                }
            }

            private <T> T runWithLimit(Callable<T> task) throws Exception {
                semaphore.acquireUninterruptibly();
                try {
                    rateLimiter.acquire();
                    return task.call();
                } finally {
                    semaphore.release();
                }
            }
        };
    }

    /**
     * 简易令牌桶速率限制器。
     */
    private static class RateLimiter {

        private final double intervalNanos;

        private long lastAcquireNanos;

        RateLimiter(double permitsPerSecond) {
            this.intervalNanos = (long) (1_000_000_000.0 / permitsPerSecond);
            this.lastAcquireNanos = System.nanoTime() - (long) intervalNanos;
        }

        synchronized void acquire() {
            long now = System.nanoTime();
            long elapsed = now - lastAcquireNanos;
            long waitNanos = (long) intervalNanos - elapsed;
            if (waitNanos > 0) {
                try {
                    Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastAcquireNanos = System.nanoTime();
        }
    }
}
