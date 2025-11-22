package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static javafx.scene.input.KeyCode.T;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {

        //缓存穿透解决
    /*    //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        //互斥锁解决缓存击穿
        return Result.ok(shop);

     */
        //逻辑过期解决
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(queryWithLogicalExpire(id));
    }

    // TODO 逻辑过期代码实现

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1获取商铺id//从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2判断缓存是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3存在直接返回
            return null;
        }
        //4命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //5判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            //5.1未过期直接返回
            return shop;
        }
        //5.2过期，尝试获取锁
        boolean lock = tryLock(LOCK_SHOP_KEY + id);
        //5.2.1判断获取锁是否成功
        if (lock) {
            //5.2.2获取锁成功，开启独立线程，缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //缓存重建就是重新查看数据库  重新将数据写入缓存
                    saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //5.2.3获取锁失败，返回旧数据
        return shop;
    }


    // TODO 互斥锁

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //获取商铺id//从redis中查询缓存
        String cacheshop = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if (StrUtil.isNotBlank(cacheshop)) {
            //存在直接返回
            Shop shop = JSONUtil.toBean(cacheshop, Shop.class);
            return shop;
        }
        if (cacheshop != null) {
            return null;
        }
        //不存在-->查询数据库
        //根据id查询数据库
        //获取互斥锁
        //判断锁是否被获取
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //失败，则休眠、
        Shop byId = null;
        try {
            if (!isLock) {
                Thread.sleep(50);
                queryWithPassThrough(id);
            }
            //成功查数据库
            byId = getById(id);
            if (byId == null) {
                //空，返回空值
                stringRedisTemplate.opsForValue()
                        .set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库存在
            //将查询到的商铺数据存入缓存
            stringRedisTemplate.opsForValue()
                    .set(key, JSONUtil.toJsonStr(byId), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }


        return byId;
    }

    // TODO 缓存穿透

    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //获取商铺id//从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;

        }
        if (shopJson != null) {
            return null;
        }

        //不存在-->查询数据库
        //根据id查询数据库
        Shop byId = getById(id);
        if (byId == null) {
            //空，返回空值
            stringRedisTemplate.opsForValue()
                    .set(key, JSONUtil.toJsonStr(byId), CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库存在
        //将查询到的商铺数据存入缓存
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(byId), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return byId;
    }

    // TODO 互斥锁配置

    //上锁
    private boolean tryLock(String key) {

        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(ifAbsent);
    }

    // TODO 删除锁配置

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    // TODO 逻辑过期配置

    public void saveShop2Redis(Long id, Long expireSeconds) {
        //查询商铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //将数据存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    public Result update(Shop shop) {
        //判断id
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
