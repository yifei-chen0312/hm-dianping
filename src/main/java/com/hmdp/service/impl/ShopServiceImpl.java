package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
        String key = CACHE_SHOP_KEY + id;
        //获取商铺id//从redis中查询缓存
        String cacheshop = stringRedisTemplate.opsForValue().get(id);
        //判断缓存是否存在
        if (StrUtil.isNotBlank(cacheshop)) {
            //存在直接返回
            Shop shop = JSONUtil.toBean(cacheshop, Shop.class);
            return Result.ok(shop);

        }
        //不存在-->查询数据库
        //根据id查询数据库
        Shop byId = getById(id);
        if (byId == null) {
            //空
            return Result.fail("商铺不存在");
        }
        //数据库存在
        //将查询到的商铺数据存入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(byId),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(byId);
    }
}
