package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.omg.PortableInterceptor.DISCARDING;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.context.Theme;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //商铺缓存
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
        //Shop  shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    //缓存穿透解决方案：
    public Shop queryWithPassThrough(Long id) {
        //1.从redis中查询商铺缓存（shop的JSON格式）
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回  这里isNotBlank只要不是字符串都是false
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断是否是空值
        if(shopJson!=null){
            //不是NULL 一定是空值
            //return Result.fail("店铺不存在！");
            return null;
        }
        //4.不存在 查询数据库
        Shop shop = getById(id);
        //5.不存在 返回错误
        if(shop == null){
            //缓存穿透解决 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //return Result.fail("店铺不存在!");
            return null;
        }
        //6.存在 写入redis 设置过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        //return Result.ok(shop);
        return  shop;
    }


    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        //1.从redis中查询商铺缓存（shop的JSON格式）
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回  这里isNotBlank只要不是字符串都是false
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断是否是空值
        if(shopJson!=null){
            //不是NULL 一定是空值
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = "lock:shop:"+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            //4.3失败 休眠并重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4成功 查询数据库
             shop = getById(id);
            //模拟重建的延迟
            Thread.sleep(1000);
            //5.不存在 返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //return Result.fail("店铺不存在!");
                return null;
            }
            //6.存在 写入redis 设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    //线程池
    private static final ExecutorService CACHE_REBULID_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        //1.从redis中查询商铺缓存（shop的JSON格式）
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)) {
            //3.不存在 直接返回
            return null;
        }
        //4.存在 先把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data,Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter((LocalDateTime.now()))) {
            //5.1未过期 直接返回
            return shop;
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
                    //缓存重建
                    this.saveShopToRedis(id,20L);
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
        return shop;
    }

    //获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",100,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //将封装的Shop存入Redis
    public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺信息
        Shop shop = getById(id);
        //模拟重建延时
        Thread.sleep(10000);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Transactional
    //更新商铺
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺ID不能为空！");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }


    //查询排序
    @Override
    public Result quertShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.是否需要根据坐标查询
        if(x == null || y == null){
            //不需要根据坐标查询，按数据库查询
            Page<Shop>page = query()
                    .eq("type_id",typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            //返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from = (current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis 根据距离排序 分页 结果:shopId distance
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(key,
                new Circle(x, y, 5000),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        //4.解析出Id
        if(results == null){
            //判空
            return Result.ok(Collections.emptyList());
        }
        //0-end
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            //会跳过from条 得到的ids会为空
            return Result.ok(Collections.emptyList());
        }
        //截取从from-end
        List<Long> ids = new ArrayList<>();
        Map<String,Distance> map = new HashMap<>();
        list.stream().skip(from).forEach(result ->{
            //获取店铺ID
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            map.put(shopIdStr,distance);
        });
        //5.根据id查询shop 要保证有序
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for(Shop shop :shops){
            //填入距离字段
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }
}
