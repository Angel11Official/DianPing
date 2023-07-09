package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    //开始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final long BITS_COUNT = 32L;
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //全局唯一ID生成
    public Long nextId(String keyPrefix){
        //符号位+时间戳+序列号
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowsecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowsecond - BEGIN_TIMESTAMP;
        //生成序列号
        //获取当前精确到天的日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        //拼接并返回
        //时间戳左移32位
        return timestamp<<BITS_COUNT |count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022,1,1,0,0,0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
