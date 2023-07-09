package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    SeckillVoucherServiceImpl seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    //代理对象
    private  IVoucherOrderService proxy;

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //在当前类初始化完毕后执行任务
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

   //消息队列处理任务定义 实现runnable接口
   private class VoucherOrderHandler implements Runnable{
       @Override
       public void run(){
           String queueName = "stream.orders";
           //死循环
           while(true){
               try {
                   //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1 STREAMS stream.orders >
                   List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                           Consumer.from("g1","c1"),
                           StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                           StreamOffset.create(queueName, ReadOffset.lastConsumed())
                   );
                   //2.判断消息是否获取成功
                   if(list==null||list.isEmpty()){
                       //2.1如果获取失败，说明没有消息，继续下一次循环
                       continue;
                   }
                   //3.如果获取成功，可以下单,解析消息中的订单信息
                   MapRecord<String,Object,Object>record = list.get(0);
                   Map<Object,Object> values = record.getValue();
                   VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);
                   handleVoucherOrder(voucherOrder);
                   //4.ACK确认 SACK stream.orders g1 id(消息ID）
                   stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
               } catch (Exception e) {
                   //出现异常 说明没有被ACK确认
                   //没有ACK确认 就要从PENDING list中处理异常
                   try {
                       handlePendingList();
                   } catch (InterruptedException ex) {
                       throw new RuntimeException(ex);
                   }
               }
           }
       }
   }

   //Pendinglist处理
    private void handlePendingList() throws InterruptedException {
        String queueName = "stream.orders";
        //死循环
        while(true){
            try {
                //1.获取Pending list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1 STREAMS stream.orders 0
                List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1","c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //2.判断消息是否获取成功
                if(list==null||list.isEmpty()){
                    //2.1如果获取失败，说明pending list没有消息，结束循环
                    break;
                }
                //3.如果获取成功，可以下单,解析消息中的订单信息
                MapRecord<String,Object,Object>record = list.get(0);
                Map<Object,Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);
                handleVoucherOrder(voucherOrder);
                //4.ACK确认 SACK stream.orders g1 id(消息ID）
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
               //如果处理过程中又出现异常 需要调用自己 不需要递归 继续下次循环
                Thread.sleep(3000);
                log.error("处理Pending-List异常");
            }
        }
    }
//    //阻塞队列
//    //当一个线程想从中取出一个元素 而队列为空 的时候 线程会阻塞
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
//    //线程任务 （内部类？匿名)
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run(){
//            //死循环
//            while(true){
//                try {
//                    //1.获取队列中的订单信息
//                    //take是一个阻塞方法
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //处理订单
        //获取用户 不能从Userholder中获取 因为是单独的线程
        Long userId = voucherOrder.getUserId();
        //创建分布式锁对象
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            //异步处理返回给前端 没有意义
            log.error("不允许重复下单!");
            return;
        }
        try{
           //获取代理对象
            //作为子线程 不可能拿到代理 要用代理来实现事务 就要从父线程中获取代理
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    //优化秒杀的创建订单
    @Transactional
    //一人一单
    //加Synchronized锁的地方？ 加在方法头？锁了整个方法 不同用户的请求都会被锁住
    //所以 对UserId加锁
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //获取用户ID
        Long userId = voucherOrder.getUserId();
        //查询订单
        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断是否存在
        if (count > 0) {
            //用户已经买过了
            log.error("不允许重复下单！");
        }
        this.save(voucherOrder);
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户ID
        Long userId = UserHolder.getUser().getId();
        //获取订单ID
        Long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT
                , Collections.emptyList()
                ,voucherId.toString(),userId.toString(),orderId.toString());
        //2.判断结果是否为0
        int i = result.intValue();
        if(i!=0){
            //2.1不为0 没有购买资格
            return Result.fail(i==1?"库存不足":"不能重复下单");
        }
        //3.获取代理对象 异步下单
        //开启独立线程，从阻塞队列中取出任务
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回结果
        return Result.ok(orderId);
    }

    //优化秒杀 --异步--LUA--阻塞队列

//    @Transactional
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户
//        Long userId = UserHolder.getUser().getId();
//        //1.执行lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT
//                , Collections.emptyList()
//                ,voucherId.toString(),userId.toString());
//        //2.判断结果是否为0
//        int i = result.intValue();
//        if(i!=0){
//            //2.1不为0 没有购买资格
//            return Result.fail(i==1?"库存不足":"不能重复下单");
//        }
//        //2.2为0 有购买资格 把下单信息保存到阻塞队列
//        Long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);
//        //3.获取代理对象 异步下单
//        //开启独立线程，从阻塞队列中取出任务
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回结果
//        return Result.ok(orderId);
//    }




    //JAVA代码抢券
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀未开始!");
//        }
//        //3.判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束！");
//        }
//        //4.判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足！");
//        }
//        //5.减少库存  加乐观锁
//        boolean success = seckillVoucherService.update().setSql("stock = stock -1") //set stock -= 1
//                .eq("voucher_id",voucherId)//where id=?
//                //.eq("stock",voucher.getStock())//加乐观锁  and stock = ?
//                .gt("stock",0)//优化乐观锁 and stock>0 提高成功率
//                .update();
//        if(!success){
//            return Result.fail("秒杀失败!");
//        }
//        //6.一人一单 悲观锁优化
//        Long userId = UserHolder.getUser().getId();
//        //如果只使用userId.toString() 返回的是一个对象 每次调用得到对象不一样 锁无法生效
//        //调用intern 从常量池中找到这样的值的String串返回
//        //所以 用户的id一样 得到的String就一样
////        synchronized ((userId.toString()).intern()) {
////            //获取代理对象
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }//事务提交后才释放锁
//
//        //分布式锁
//        //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:"+userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            //获取失败,返回错误/重试
//            return Result.fail("不允许重复下单！");
//        }
//        try{
//           //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }





//    @Transactional
//    //一人一单
//    //加Synchronized锁的地方？ 加在方法头？锁了整个方法 不同用户的请求都会被锁住
//    //所以 对UserId加锁
//    public Result createVoucherOrder(Long voucherId){
//        Long userId = UserHolder.getUser().getId();
//
//        //不能在这里加锁的原因
//        /*
//          代码运行结束后释放锁，再提交事务；
//          而释放锁以后，其他线程就可以进入，此时如果事务没提交完成，那么就会出现错误；
//          还有可能出现并发问题
//          所有锁加在整个函数外面
//        * */
//        //6.1 查询订单
//        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        //6.2 判断是否存在
//        if (count > 0) {
//            //用户已经买过了
//            return Result.fail("用户已经抢购过！");
//        }
//        // 6.3 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //订单ID 用户ID 秒杀券ID
//        Long orderId = redisIdWorker.nextId("Order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        this.save(voucherOrder);
//        //7.返回结果
//        return Result.ok(voucherOrder.getId());
//    }


}
