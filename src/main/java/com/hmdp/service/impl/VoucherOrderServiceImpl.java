package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    private IVoucherOrderService proxy;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //创建一个单线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //@PostConstruct注解：在类刚初始化的时候执行被注解的命令
    @PostConstruct
    //创建初始方法，将阻塞队列里的判断逻辑移到另外一个线程
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    //为下单逻辑增加分布式锁(这里在Lua脚本中已经分布式的完成了判断业务，所以无需再加分布式锁，这里可以删除)
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1. 获取用户
        Long userId = voucherOrder.getUserId();
        //2. 创建锁对象，作为兜底方案
        RLock Lock = redissonClient.getLock("order:" + userId);
        //3. 获取锁
        boolean isLock = Lock.tryLock();
        //4. 判断是否获取锁成功
        if (!isLock) {
            log.error("不允许重复下单!");
            return;
        }
        try {
            //  5. 使用代理对象，由于这里是另外一个线程，
            //voucherOrderService.createVoucherOrder(voucherOrder);
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            Lock.unlock();
        }
    }
    String queueName = "stream.orders";
    //下单逻辑，在另外一个线程异步执行
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //ReadOffset.lastConsumed()底层就是 '>'
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //2. 判断消息队列是否为空
                    if (records.isEmpty() || records == null) {
                        continue;
                    }
                    //抽取对象
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                    handleVoucherOrder(voucherOrder);
                    //5. 手动ACK，SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                //1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        //ReadOffset.lastConsumed()底层就是 '>'
                        StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                //2. 判断消息队列是否为空
                if (records.isEmpty() || records == null) {
                    break;
                }
                //抽取对象
                MapRecord<String, Object, Object> record = records.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                handleVoucherOrder(voucherOrder);
                //5. 手动ACK，SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.error("订单处理异常", e);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }
    /*private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取队列中的订单信息
                    // take方法为阻塞方法，如果没获取元素则阻塞，所以这里可以用死循环
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }*/

    @Override
    public Result seckillVoucher(Long voucherId) {

        //获取用户
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        //1、执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        //2、判断结果是为零
        int r = result.intValue();
        if (r != 0) {
            //2.1、不为0，代表没有购买资格
            return Result.fail(r == 1 ? "秒杀还未开始" : "库存不足");
        }

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);

    }


    /*@Override
    public Result seckillVoucher(Long voucherId) {

        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1、执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2、判断结果是为零
        int r = result.intValue();
        if (r != 0) {
            //2.1、不为0，代表没有购买资格
            return Result.fail(r == 1 ? "秒杀还未开始" : "库存不足");
        }

        //2.2、为0，代表可以购买
        //生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列

            orderTasks.add(voucherOrder);

         proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);

    }*/


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //查询用户id
        Long userId = voucherOrder.getUserId();

        // 根据userId和voucherId来查询订单
        Integer count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder).count();
        //扣减库存
        if (count > 0) {
            return ;
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder).eq("stock", 0)
                .update();
        if (!success) {
            return ;
        }

        save(voucherOrder);
        //返回订单id

    }
}


/*    @Override
    public Result seckillVoucher(Long voucherId) {

        //获取优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始");
        }
        //是否结束
        if (voucher.getBeginTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1 ) {
            return Result.fail("库存不足");
        }
        //库存充足
        Long userId = UserHolder.getUser().getId();
        //synchronized (userId.toString().intern()) {
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("lock:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean islock = lock.tryLock();
        if (!islock) {
            return Result.fail("下单失败");
        }
        try {
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
        //return createVoucherOrder(voucherId);

       // }
    }*/


