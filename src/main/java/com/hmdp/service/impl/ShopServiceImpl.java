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
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
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

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //用逻辑过期来解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,
                Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }

        return Result.ok(shop);
    }

//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    public Shop queryWithLogicalExpire(Long id){
//        // 1. 拼接 Redis 中的缓存 key（每个店铺一个独立的 key）
//        String key = CACHE_SHOP_KEY + id;
//
//        // 2. 先从 Redis 中查询店铺的 JSON 字符串（使用 String 类型存储）
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 3. 判断是否命中缓存（StrUtil.isNotBlank 判断字符串不为 null、不为空串、不为空白）
//        if (StrUtil.isBlank(shopJson)) {
//            return null;
//        }
//
//        //4.命中，需要把json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //5.判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())){
//            //5.1未过期，直接返回店铺信息
//            return shop;
//        }
//
//        //5.2过期，需要缓存重建
//
//        //6.缓存重建
//        //6.1获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        //6.2判断是否获取锁成功
//        if(isLock){
//            //6.3成功，开启独立线程，实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id,30L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    //释放锁
//                    unlock(lockKey);
//                }
//            });
//        }
//
//        return shop;
//    }

    //互斥锁防止缓存击穿
//    public Shop queryWithMutex(Long id){
//        // 1. 拼接 Redis 中的缓存 key（每个店铺一个独立的 key）
//        //    CACHE_SHOP_KEY 是常量，如 "cache:shop:"
//        String key = CACHE_SHOP_KEY + id;
//
//        // 2. 先从 Redis 中查询店铺的 JSON 字符串（使用 String 类型存储）
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 3. 判断是否命中缓存（StrUtil.isNotBlank 判断字符串不为 null、不为空串、不为空白）
//        //    - 如果 shopJson 是正常的对象 JSON（如 {"id":1,"name":"星巴克"}），这里为 true → 直接返回
//        //    - 如果是空值缓存（如 ""），这里为 false → 进入下一个判断
//        if (StrUtil.isNotBlank(shopJson)) {
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//
//        //缓存穿透逻辑
//        if (shopJson != null) {  // 注意：这里 shopJson != null 且上一步 isNotBlank 为 false → 一定是 ""
//
//            return null;
//        }
//
//        //****实现缓存重建（缓存击穿）
//        //ji.1.获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//            //ji.2.判断是否获取成功
//            if(!isLock){
//                //ji.3.失败，则休眠重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            //ji.4.成功，根据id查询数据库
//            shop = getById(id);
//
//            // 9. 数据库中也不存在该店铺
//            if (shop == null) {
//                // 10. 为了防止缓存穿透（恶意用户用不存在的 id 刷接口，导致每次都打到数据库）
//                //     我们主动向 Redis 写入一个“空值”（这里用 "" 代表空），并设置短过期时间
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                // 11. 返回错误信息给前端
//                return null;
//            }
//
//            // 12. 数据库中存在该店铺 → 写入 Redis 缓存，设置正常过期时间（例如 30 分钟）
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            //释放互斥锁
//            unlock(lockKey);
//        }
//
//        // 13. 返回查询到的店铺信息
//        return shop;
//    }

    //缓存穿透代码
//    public Shop queryWithPassThrough(Long id){
//        // 1. 拼接 Redis 中的缓存 key（每个店铺一个独立的 key）
//        //    CACHE_SHOP_KEY 是常量，如 "cache:shop:"
//        String key = CACHE_SHOP_KEY + id;
//
//        // 2. 先从 Redis 中查询店铺的 JSON 字符串（使用 String 类型存储）
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 3. 判断是否命中缓存（StrUtil.isNotBlank 判断字符串不为 null、不为空串、不为空白）
//        //    - 如果 shopJson 是正常的对象 JSON（如 {"id":1,"name":"星巴克"}），这里为 true → 直接返回
//        //    - 如果是空值缓存（如 ""），这里为 false → 进入下一个判断
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 4. 缓存命中（真实数据）→ 将 JSON 反序列化为 Shop 对象
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            // 5. 直接返回成功结果（不需要再查数据库，性能最高）
//            return shop;
//        }
//
//        // 6. 走到这里说明 shopJson 要么是 null（未命中），要么是 ""（我们自己写的空值）
//        //    单独判断是否为 ""（空字符串），这是为了区分“未缓存”和“缓存了空值”
//        if (shopJson != null) {  // 注意：这里 shopJson != null 且上一步 isNotBlank 为 false → 一定是 ""
//            // 7. 命中了我们自己写入的“空值缓存” → 说明数据库中确实不存在这个店铺
//            //    直接返回错误信息（避免再次查数据库）
//            return null;
//        }
//
//        // 8. 走到这里说明 Redis 中根本没有这个 key（真正的缓存未命中）
//        //    根据 id 查询数据库（MyBatis-Plus 的 getById 方法）
//        Shop shop = getById(id);
//
//        // 9. 数据库中也不存在该店铺
//        if (shop == null) {
//            // 10. 为了防止缓存穿透（恶意用户用不存在的 id 刷接口，导致每次都打到数据库）
//            //     我们主动向 Redis 写入一个“空值”（这里用 "" 代表空），并设置短过期时间
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            // 11. 返回错误信息给前端
//            return null;
//        }
//
//        // 12. 数据库中存在该店铺 → 写入 Redis 缓存，设置正常过期时间（例如 30 分钟）
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        // 13. 返回查询到的店铺信息
//        return shop;
//    }

//    //尝试获取锁
//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }

//    //释放锁
//    private void unlock(String key) {
//        stringRedisTemplate.delete(key);
//    }

    //逻辑过期时间
//    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
//        //1.查询店铺数据
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        //2.封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        //3.写入Redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
//    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.跟新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
