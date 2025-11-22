package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    //具体业务名称，要做key
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "—";

    @Override
    public boolean trylock(long timeoutSec) {
        //获取线程
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean ifAbsent = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        //返回Boolean包装，防止基础类型出现null问题
        return Boolean.TRUE.equals(ifAbsent);
    }

    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String key = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(key)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
