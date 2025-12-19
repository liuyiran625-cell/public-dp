package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        // 1. 拼接 Redis 中的缓存 key（每个店铺一个独立的 key）
        //    CACHE_SHOP_KEY 是常量，如 "cache:shop:"
        String key = keyPrefix + id;

        // 2. 先从 Redis 中查询店铺的 JSON 字符串（使用 String 类型存储）
        String Json = stringRedisTemplate.opsForValue().get(key);

        // 3. 判断是否命中缓存（StrUtil.isNotBlank 判断字符串不为 null、不为空串、不为空白）
        //    - 如果 shopJson 是正常的对象 JSON（如 {"id":1,"name":"星巴克"}），这里为 true → 直接返回
        //    - 如果是空值缓存（如 ""），这里为 false → 进入下一个判断
        if (StrUtil.isNotBlank(Json)) {
            return JSONUtil.toBean(Json, type);
        }

        // 6. 走到这里说明 shopJson 要么是 null（未命中），要么是 ""（我们自己写的空值）
        //    单独判断是否为 ""（空字符串），这是为了区分“未缓存”和“缓存了空值”
        if (Json != null) {  // 注意：这里 shopJson != null 且上一步 isNotBlank 为 false → 一定是 ""
            // 7. 命中了我们自己写入的“空值缓存” → 说明数据库中确实不存在这个店铺
            //    直接返回错误信息（避免再次查数据库）
            return null;
        }

        // 8. 走到这里说明 Redis 中根本没有这个 key（真正的缓存未命中）
        //    根据 id 查询数据库（MyBatis-Plus 的 getById 方法）
        R r = dbFallback.apply(id);

        // 9. 数据库中也不存在该店铺
        if (r == null) {
            // 10. 为了防止缓存穿透（恶意用户用不存在的 id 刷接口，导致每次都打到数据库）
            //     我们主动向 Redis 写入一个“空值”（这里用 "" 代表空），并设置短过期时间
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 11. 返回错误信息给前端
            return null;
        }

        // 12. 数据库中存在该店铺 → 写入 Redis 缓存，设置正常过期时间（例如 30 分钟）

        this.set(key, r, time, unit);
        // 13. 返回查询到的店铺信息
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID>  R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID,R> dbFallback,
            Long time, TimeUnit unit) {
        // 1. 拼接 Redis 中的缓存 key（每个店铺一个独立的 key）
        String key = keyPrefix + id;

        // 2. 先从 Redis 中查询店铺的 JSON 字符串（使用 String 类型存储）
        String Json = stringRedisTemplate.opsForValue().get(key);

        // 3. 判断是否命中缓存（StrUtil.isNotBlank 判断字符串不为 null、不为空串、不为空白）
        if (StrUtil.isBlank(Json)) {
            return null;
        }

        //4.命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return r;
        }

        //5.2过期，需要缓存重建

        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if(isLock){
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        return r;
    }

    //尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
