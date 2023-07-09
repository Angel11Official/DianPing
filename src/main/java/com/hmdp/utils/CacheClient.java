package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //设置缓存
    public void set(String key, Object value, Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //设置逻辑过期缓存
    public void setWithLogicalExpire(String key, Object value, Long time , TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData =  new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透解决方案：
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                         Function<ID,R>deFallback,Long time,TimeUnit unit) {
        String key = keyPrefix+id;
        //1.从redis中查询商铺缓存 JSON格式
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)) {
            //3.存在 直接返回  这里isNotBlank只要不是字符串都是false
            return JSONUtil.toBean(json, type);
        }
        //判断是否是空值
        if(json!=null){
            //不是NULL 一定是空值
            return null;
        }
        //4.不存在 查询数据库
        R  r = deFallback.apply(id);
        //5.不存在 返回错误
        if(r == null){
            //缓存穿透解决 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在 写入redis 设置过期时间
        this.set(key,r,time,unit);
        return r;
    }

    //线程池
    private static final ExecutorService CACHE_REBULID_EXECUTOR = Executors.newFixedThreadPool(10);

    //获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",100,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R>type,
                                           Function<ID,R>deFallback,Long time,TimeUnit unit) {
        String key = keyPrefix+id;
        //1.从redis中查询商铺缓存（shop的JSON格式）
        String json = stringRedisTemplate.opsForValue().get(key);
        log.debug(String.valueOf(StrUtil.isBlank(json)));
        //2.判断是否存在
        if(StrUtil.isBlank(json)) {
            //3.不存在 直接返回
            return null;
        }
        //4.存在 先把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        R r  = JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter((LocalDateTime.now()))) {
            //5.1未过期 直接返回
            return r;
        }
        //5.2已经过期 需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否成功获取成功
        if(isLock){
            //6.3成功 开启一个线程 实现缓存重建
            CACHE_REBULID_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = deFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    //异常处理
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4无论是否成功 返回过期信息
        return r;
    }


}
