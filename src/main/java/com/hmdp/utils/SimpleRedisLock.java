package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    //锁名称不能写死 要和业务有关
    private String name;
    private static final String KEY_PREFIX = "LOCK:";
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";
    //提前读取脚本
    private static final DefaultRedisScript<Long>UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //构造函数 初始化
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //获取锁
    @Override
    public boolean tryLock(long timeout) {
        //UUID + 获取线程标识
         String threadId = ID_PREFIX + Thread.currentThread().getId();
        //执行Setnx
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId,timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }


    //LUA脚本改进释放锁
    @Override
    public void unLock() {
        //调用lua脚本 判断+删除
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name),ID_PREFIX + Thread.currentThread().getId());
    }
    //释放锁
//    @Override
//    public void unLock() {
//        //UUID + 获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
//        //释放锁前要比较标识是否一致
//        if(id.equals(threadId)){
//            //一致 释放
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//    }
}
