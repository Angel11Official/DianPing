//package com.hmdp;
//
//import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
//import com.hmdp.entity.Shop;
//import com.hmdp.entity.User;
//import com.hmdp.entity.Voucher;
//import com.hmdp.service.impl.ShopServiceImpl;
//import com.hmdp.service.impl.UserServiceImpl;
//import com.hmdp.service.impl.VoucherServiceImpl;
//import com.hmdp.utils.CacheClient;
//import com.hmdp.utils.RedisIdWorker;
//import com.hmdp.utils.UserHolder;
//import lombok.extern.slf4j.Slf4j;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.data.geo.Point;
//import org.springframework.data.redis.connection.RedisGeoCommands;
//import org.springframework.data.redis.core.StringRedisTemplate;
//
//import javax.annotation.Resource;
//
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
//import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
//
//
//@Slf4j
//@SpringBootTest
//class HmDianPingApplicationTests {
//      @Resource
//      private ShopServiceImpl shopService;
//
//      @Resource
//      private CacheClient cacheClient;
//
//      @Resource
//      private RedisIdWorker redisIdWorker;
//
//      @Resource
//      private VoucherServiceImpl voucherService;
//
//      @Resource
//      private RedissonClient redissonClient;
//
//      @Resource
//      private StringRedisTemplate stringRedisTemplate;
//
//      private RLock lock;
//
//
//      private ExecutorService es = Executors.newFixedThreadPool(500);
//
//      @Test
//      void TestShop() throws InterruptedException {
//          Shop shop = shopService.getById(1L);
//          cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.SECONDS);
//      }
//
//      @Test
//      void TestIdWorker() throws InterruptedException {
//          CountDownLatch latch = new CountDownLatch(300);
//          Runnable task = () ->{
//              for(int i=0;i<100;++i){
//                  long id = redisIdWorker.nextId("order");
//                  System.out.println("id = " + id);
//              }
//              latch.countDown();
//          };
//          long begin = System.currentTimeMillis();
//          for(int i=0;i<300;++i){
//              es.submit(task);
//          }
//          latch.await();
//          long end = System.currentTimeMillis();
//          System.out.println(end-begin);
//      }
//
//      @Test
//      void TestVoucher(){
//          List<Voucher> list = voucherService.queryVoucherOfShop(1L);
//          for(Voucher voucher:list){
//              if(voucher.getType()==1){
//                  System.out.println(voucher.getBeginTime());
//                  System.out.println(voucher.getBeginTime().getHour());
//                  System.out.println(voucher.getBeginTime().getMinute());
//                  System.out.println(voucher.getBeginTime().getSecond());
//              }
//          }
//      }
//
//      @BeforeEach
//      void setUp(){
//          lock = redissonClient.getLock("order");
//      }
//
//      @Test
//       void method1(){
//          //尝试获取锁
//          boolean isLock = lock.tryLock();
//          if(!isLock){
//              log.error("获取锁失败。。。1");
//              return ;
//          }
//          try{
//              log.info("获取锁成功。。。1");
//              method2();
//              log.info("执行业务。。。1");
//          }finally {
//              log.warn("准备释放锁。。。1");
//              lock.unlock();
//          }
//      }
//      void method2(){
//          //尝试获取锁
//          boolean isLock = lock.tryLock();
//          if(!isLock){
//              log.error("获取锁失败。。。2");
//              return ;
//          }
//          try{
//              log.info("获取锁成功。。。2");
//
//              log.info("执行业务。。。2");
//          }finally {
//              log.warn("准备释放锁。。。2");
//              lock.unlock();
//          }
//      }
//
//      @Test
//      void loadShopData(){
//          //1.查询店铺信息
//          List<Shop>list = shopService.list();
//          //2.把店铺分组 根据typeId分组 id一致的放到同一个GEO集合
//          Map<Long,List<Shop>> map = list.stream()
//                  .collect(Collectors.groupingBy(Shop::getTypeId));
//          //3.分批完成写入Redis
//          for(Map.Entry<Long,List<Shop>> entry:map.entrySet()){
//              //3.1获取类型ID
//              Long typeId = entry.getKey();
//              //3.2获取同类型店铺集合
//              List<Shop>value = entry.getValue();
//              List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
//              //3.3写入redis geoadd key 经度 维度 member
//              String key = "shop:geo:" + typeId;
//              for(Shop shop:value){
//                  //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
//                  locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
//              }
//              //批量添加
//              stringRedisTemplate.opsForGeo().add(key,locations);
//          }
//
//      }
//
//    @Test
//    void UVTest(){
//          String[] values = new String[1000];
//          int j = 0;
//          for(int i =0;i<1000000;++i){
//              j = i % 1000;
//              values[j] = "user_" + i;
//              if(j == 999){
//                  //插入数据
//                  stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
//              }
//          }
//          //统计数量
//          Long size = stringRedisTemplate.opsForHyperLogLog().size("hl2");
//           System.out.println(size);
//    }
//
//}
