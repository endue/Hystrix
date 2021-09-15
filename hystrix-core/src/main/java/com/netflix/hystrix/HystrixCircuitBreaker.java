/**
 * Copyright 2012 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;
import rx.Subscriber;
import rx.Subscription;

/**
 * 熔断器
 * Circuit-breaker logic that is hooked into {@link HystrixCommand} execution and will stop allowing executions if failures have gone past the defined threshold.
 * <p>
 * The default (and only) implementation  will then allow a single retry after a defined sleepWindow until the execution
 * succeeds at which point it will again close the circuit and allow executions again.
 */
public interface HystrixCircuitBreaker {

    /**
     * 是否允许请求
     * Every {@link HystrixCommand} requests asks this if it is allowed to proceed or not.  It is idempotent and does
     * not modify any internal state, and takes into account the half-open logic which allows some requests through
     * after the circuit has been opened
     * 
     * @return boolean whether a request should be permitted
     */
    boolean allowRequest();

    /**
     * 熔断器是否开启(也就是跳闸了)
     * Whether the circuit is currently open (tripped).
     * 
     * @return boolean state of circuit breaker
     */
    boolean isOpen();

    /**
     * 当处于半打开状态时，在{@link HystrixCommand}成功执行时调用，作为反馈机制的一部分
     * Invoked on successful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     */
    void markSuccess();

    /**
     * 当处于半打开状态时，在{@link HystrixCommand}不成功执行时调用，作为反馈机制的一部分
     * Invoked on unsuccessful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     */
    void markNonSuccess();

    /**
     * 在命令执行开始时调用，判断是否可以执行请求
     * Invoked at start of command execution to attempt an execution.  This is non-idempotent - it may modify internal
     * state.
     */
    boolean attemptExecution();

    /**
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    class Factory {
        // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
        // key是HystrixCommandKey.name()
        private static ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakersByCommand = new ConcurrentHashMap<String, HystrixCircuitBreaker>();

        /**
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey}.
         * <p>
         * This is thread-safe and ensures only 1 {@link HystrixCircuitBreaker} per {@link HystrixCommandKey}.
         * 
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @param group
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param properties
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param metrics
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key, HystrixCommandGroupKey group, HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            // this should find it for all but the first time
            HystrixCircuitBreaker previouslyCached = circuitBreakersByCommand.get(key.name());
            if (previouslyCached != null) {
                return previouslyCached;
            }

            // if we get here this is the first time so we need to initialize

            // Create and add to the map ... use putIfAbsent to atomically handle the possible race-condition of
            // 2 threads hitting this point at the same time and let ConcurrentHashMap provide us our thread-safety
            // If 2 threads hit here only one will get added and the other will get a non-null response instead.
            HystrixCircuitBreaker cbForCommand = circuitBreakersByCommand.putIfAbsent(key.name(), new HystrixCircuitBreakerImpl(key, group, properties, metrics));
            if (cbForCommand == null) {
                // this means the putIfAbsent step just created a new one so let's retrieve and return it
                return circuitBreakersByCommand.get(key.name());
            } else {
                // this means a race occurred and while attempting to 'put' another one got there before
                // and we instead retrieved it and will now return it
                return cbForCommand;
            }
        }

        /**
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey} or null if none exists.
         * 
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key) {
            return circuitBreakersByCommand.get(key.name());
        }

        /**
         * Clears all circuit breakers. If new requests come in instances will be recreated.
         */
        /* package */static void reset() {
            circuitBreakersByCommand.clear();
        }
    }


    /**
     * The default production implementation of {@link HystrixCircuitBreaker}.
     * 
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    /* package */class HystrixCircuitBreakerImpl implements HystrixCircuitBreaker {
        private final HystrixCommandProperties properties;
        private final HystrixCommandMetrics metrics;

        enum Status {
            CLOSED, OPEN, HALF_OPEN;
        }

        /**
         * 记录熔断器状态
         */
        private final AtomicReference<Status> status = new AtomicReference<Status>(Status.CLOSED);
        /**
         * 记录上一次触发熔断器的时间戳，如果上一次熔断器状态为关闭则该值为-1
         */
        private final AtomicLong circuitOpened = new AtomicLong(-1);
        /**
         * 基于HystrixCommandMetrics对请求量统计Observable的订阅
         */
        private final AtomicReference<Subscription> activeSubscription = new AtomicReference<Subscription>(null);

        protected HystrixCircuitBreakerImpl(HystrixCommandKey key, HystrixCommandGroupKey commandGroup, final HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            this.properties = properties;
            this.metrics = metrics;

            //On a timer, this will set the circuit between OPEN/CLOSED as command executions occur
            Subscription s = subscribeToStream();
            activeSubscription.set(s);
        }

        private Subscription subscribeToStream() {
            /*
             * This stream will recalculate the OPEN/CLOSED status on every onNext from the health stream
             */
            return metrics.getHealthCountsStream()
                    .observe()
                    .subscribe(new Subscriber<HealthCounts>() {
                        @Override
                        public void onCompleted() {

                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onNext(HealthCounts hc) {
                            // check if we are past the statisticalWindowVolumeThreshold
                            // 判断总请求数量是否超过配置阈值，若未超过，则不改变熔断器状态
                            if (hc.getTotalRequests() < properties.circuitBreakerRequestVolumeThreshold().get()) {
                                // we are not past the minimum volume threshold for the stat window,
                                // so no change to circuit status.
                                // if it was CLOSED, it stays CLOSED
                                // if it was half-open, we need to wait for a successful command execution
                                // if it was open, we need to wait for sleep window to elapse
                            } else {
                                // 判断请求错误率是否超过配置错误率阈值，若未超过则不改变熔断器状态；若超过，则更新熔断器状态为打开
                                if (hc.getErrorPercentage() < properties.circuitBreakerErrorThresholdPercentage().get()) {
                                    //we are not past the minimum error threshold for the stat window,
                                    // so no change to circuit status.
                                    // if it was CLOSED, it stays CLOSED
                                    // if it was half-open, we need to wait for a successful command execution
                                    // if it was open, we need to wait for sleep window to elapse
                                } else {
                                    // our failure rate is too high, we need to set the state to OPEN
                                    if (status.compareAndSet(Status.CLOSED, Status.OPEN)) {
                                        circuitOpened.set(System.currentTimeMillis());
                                    }
                                }
                            }
                        }
                    });
        }

        /**
         * 当调用attemptExecution()方法成功后，调用该方法，关闭熔断器
         */
        @Override
        public void markSuccess() {
            if (status.compareAndSet(Status.HALF_OPEN, Status.CLOSED)) {
                //This thread wins the race to close the circuit - it resets the stream to start it over from 0
                metrics.resetStream();
                Subscription previousSubscription = activeSubscription.get();
                if (previousSubscription != null) {
                    previousSubscription.unsubscribe();
                }
                Subscription newSubscription = subscribeToStream();
                activeSubscription.set(newSubscription);
                circuitOpened.set(-1L);
            }
        }

        /**
         * 当调用attemptExecution()方法失败后，调用该方法，开启熔断器
         */
        @Override
        public void markNonSuccess() {
            if (status.compareAndSet(Status.HALF_OPEN, Status.OPEN)) {
                //This thread wins the race to re-open the circuit - it resets the start time for the sleep window
                circuitOpened.set(System.currentTimeMillis());
            }
        }

        /**
         * 熔断器是否开启
         * @return
         */
        @Override
        public boolean isOpen() {
            // 强制开启
            if (properties.circuitBreakerForceOpen().get()) {
                return true;
            }
            // 强制关闭
            if (properties.circuitBreakerForceClosed().get()) {
                return false;
            }
            // 发生了熔断
            return circuitOpened.get() >= 0;
        }

        /**
         * 是否允许请求通过
         * @return
         */
        @Override
        public boolean allowRequest() {
            // 强制开启熔断器，不允许请求通过
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            // 强制关闭熔断器，允许请求通过
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            // 为发生熔断，允许请求通过
            if (circuitOpened.get() == -1) {
                return true;
            } else {
                if (status.get().equals(Status.HALF_OPEN)) {
                    return false;
                } else {
                    return isAfterSleepWindow();
                }
            }
        }

        /**
         * 在睡眠窗口之后，什么意思？
         * A：也就是之前触发了熔断，之后再次处理请求时判断是否已经过了睡眠窗口期，如果过了那么就调用正常的业务逻辑
         * @return
         */
        private boolean isAfterSleepWindow() {
            // 触发熔断的时间戳以及当前时间戳
            final long circuitOpenTime = circuitOpened.get();
            final long currentTime = System.currentTimeMillis();
            // 获取窗口睡眠时间
            final long sleepWindowTime = properties.circuitBreakerSleepWindowInMilliseconds().get();
            // 当前时间已超过窗口睡眠时间则运行请求通过，否则拒绝
            return currentTime > circuitOpenTime + sleepWindowTime;
        }

        /**
         *
         * @return
         */
        @Override
        public boolean attemptExecution() {
            // 1. 熔断器是否强制开启
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            // 2. 熔断器是否强制关闭
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            // 3. 熔断器未开启
            if (circuitOpened.get() == -1) {
                return true;
            // 4. 熔断器已开启(也就是之前的操作触发了熔断)
            } else {
                // 判断是否满足调用正常逻辑的时间间隔
                if (isAfterSleepWindow()) {
                    //only the first request after sleep window should execute
                    //if the executing command succeeds, the status will transition to CLOSED
                    //if the executing command fails, the status will transition to OPEN
                    //if the executing command gets unsubscribed, the status will transition to OPEN
                    // 满足时CAS修改熔断器状态为HALF_OPEN
                    if (status.compareAndSet(Status.OPEN, Status.HALF_OPEN)) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
    }

    /**
     * An implementation of the circuit breaker that does nothing.
     * 
     * @ExcludeFromJavadoc
     */
    /* package */static class NoOpCircuitBreaker implements HystrixCircuitBreaker {

        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void markSuccess() {

        }

        @Override
        public void markNonSuccess() {

        }

        @Override
        public boolean attemptExecution() {
            return true;
        }
    }

}
