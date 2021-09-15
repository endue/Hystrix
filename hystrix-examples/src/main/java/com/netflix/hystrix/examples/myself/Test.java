package com.netflix.hystrix.examples.myself;

/**
 * @Author:
 * @Description:
 * @Date: 2021/9/11 18:27
 * @Version: 1.0
 */
public class Test {

    public static void main(String[] args) {
        SelfHystrixCommand hc = new SelfHystrixCommand(111L);
        String res = hc.execute();
        System.out.println(res);
    }
}
