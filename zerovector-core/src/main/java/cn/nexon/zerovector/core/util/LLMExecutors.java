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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
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
     * 按 rps 值全局共享的限流器。
     * <p>每个管理器/构建器都会创建自己的执行器，若各持有限流器，
     * 聚合请求速率会成倍放大；共享后全 JVM 对同一 rps 配置共用一个节拍。
     * <p>Semaphore（并发度）仍按执行器实例隔离，不在此列。
     */
    private static final ConcurrentHashMap<Double, RateLimiter> SHARED_RATE_LIMITERS = new ConcurrentHashMap<>();

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
        // rps <= 0 视为不限速（否则除零会产生无限等待间隔）；
        // RateLimiter 按 rps 全局共享：多个执行器各自建实例时聚合速率会成倍放大
        RateLimiter rateLimiter = rps > 0
            ? SHARED_RATE_LIMITERS.computeIfAbsent(rps, RateLimiter::new)
            : null;
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
                // ExecutorService 契约要求阻塞等待全部完成，复用带超时版本（无限等待）
                return invokeAll(tasks, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                                  long timeout, TimeUnit unit) {
                long deadline = System.nanoTime() + unit.toNanos(timeout);
                List<Future<T>> futures = new ArrayList<>(tasks.size());
                for (Callable<T> task : tasks) {
                    futures.add(submit(task));
                }
                for (Future<T> future : futures) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        future.cancel(true);
                        continue;
                    }
                    try {
                        future.get(remaining, TimeUnit.NANOSECONDS);
                    } catch (CancellationException | ExecutionException ignored) {
                        // 契约：任务异常保留在 Future 中，由调用方取出
                    } catch (TimeoutException e) {
                        future.cancel(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        future.cancel(true);
                    }
                }
                return futures;
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
                    if (rateLimiter != null) {
                        rateLimiter.acquire();
                    }
                    command.run();
                } finally {
                    semaphore.release();
                }
            }

            private <T> T runWithLimit(Callable<T> task) throws Exception {
                semaphore.acquireUninterruptibly();
                try {
                    if (rateLimiter != null) {
                        rateLimiter.acquire();
                    }
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
