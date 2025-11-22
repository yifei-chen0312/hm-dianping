package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /* 开始时间戳 */

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //开始时间
    private static final  Long BEGIN_TIMESTAMP = 1735689600L;
    //序列号位数
    public static final Long COUNT_BITS = 32L;

    public long nextId(String keyPrefix){
        //1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long currentTime = now.toEpochSecond(ZoneOffset.UTC);

        //timeStamp为时间戳
        long timeStamp =  currentTime - BEGIN_TIMESTAMP;


        //2. 生成序列号
        //2.1 获取当前日期  精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 自增长
        //不会担心空指针问题  他会自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        //3. 拼接并返回
        //拼接long类型用位运算
        //让timeStamp向左移动32位
        //用或运算将count填充到timeStamp
        return  timeStamp << COUNT_BITS|count;
    }

}
