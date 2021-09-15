package com.netflix.hystrix.examples.myself;

import com.netflix.hystrix.*;

/**
 * @Author:
 * @Description: HystrixCommand是有状态的bean
 * 如果交给spring管理需要设置为prototype并且在使用的地方需调用applicationContext去获取
 * 参考{@link AbstractCommand#toObservable}
 * @Date: 2021/9/11 18:22
 * @Version: 1.0
 */
public class SelfHystrixCommand extends HystrixCommand<String> {

    private Long userId;

    protected SelfHystrixCommand(Long userId) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserServiceCommandGroup"))
        .andCommandKey(HystrixCommandKey.Factory.asKey("SelfHystrixCommand"))
        .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                .withRequestLogEnabled(true)
        .withRequestLogEnabled(true)
        .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.THREAD)
        .withCircuitBreakerSleepWindowInMilliseconds(1000)));
        this.userId = userId;

    }

    @Override
    protected String run() throws Exception {
        int i = 1/0;
        return userId + "www";
    }

    @Override
    protected String getFallback() {
        return "降级name";
    }
}
