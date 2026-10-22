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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 带并发度限制的虚拟线程执行器，用于并行化构建阶段的 LLM 调用。
 *
 * <p>只限制并发度，不再做速率限制——全局 RPS 由
 * {@code RateLimitedLLMProvider} 统一负责，避免两套节拍互相叠加。
 */
public final class LLMExecutors {

    private LLMExecutors() {
    }

    public static ExecutorService create(ConcurrencyProperties props) {
        int maxConcurrent = Math.max(1, props.getMaxConcurrentRequests());
        Semaphore semaphore = new Semaphore(maxConcurrent);
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
                    command.run();
                } finally {
                    semaphore.release();
                }
            }

            private <T> T runWithLimit(Callable<T> task) throws Exception {
                semaphore.acquireUninterruptibly();
                try {
                    return task.call();
                } finally {
                    semaphore.release();
                }
            }
        };
    }
}
