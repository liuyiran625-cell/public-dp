package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        // 1. 定义 Redis 中的缓存 key，用于存储店铺类型列表
        String key = "show:type:list";

        // 2. 从 Redis 的 List 结构中查询整个列表（range 0 到 -1 表示取全部元素）
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 3. 判断 Redis 是否命中缓存
        if ( shopTypeList.size() != 0) {
            // 4. 缓存命中 → 需要将每个 JSON 字符串反序列化为 ShopType 对象
            List<ShopType> typeList = new ArrayList<>();
            for (String shopType : shopTypeList) {
                // 使用 Hutool 的 JSONUtil 将字符串转为 ShopType 对象
                typeList.add(JSONUtil.toBean(shopType, ShopType.class));
            }
            // 5. 直接返回缓存数据（不查数据库，性能最高）
            return Result.ok(typeList);
        }

        // 6. 缓存未命中 → 查询数据库
        //    使用 MyBatis-Plus 的链式查询，按 sort 字段升序排列（前端通常要求有序展示）
        List<ShopType> typeList = this.query()
                .orderByAsc("sort")  // 按 sort 字段升序（sort 值越小越靠前）
                .list();

        // 7. 如果数据库为空，缓存空结果到 Redis，防止缓存穿透（设置短 TTL）
        if (typeList.isEmpty()) {
            stringRedisTemplate.opsForValue().set(key, "[]", 5, TimeUnit.MINUTES);  // 缓存空数组，短过期时间
            return Result.ok(typeList);  // 返回空列表
        }
        // 8. 将数据库查询结果转为 JSON 字符串列表，准备存入 Redis
        List<String> stringList = new ArrayList<>();
        for (ShopType shopType : typeList) {
            // 使用 Hutool 的 JSONUtil 将 ShopType 对象转为 JSON 字符串
            stringList.add(JSONUtil.toJsonStr(shopType));
        }

        // 9. 将字符串列表从右边批量推入 Redis 的 List 结构
        //    rightPushAll 会一次性把所有元素添加到 List 尾部
        stringRedisTemplate.opsForList().rightPushAll(key, stringList);

        // 10. （缺失的部分！重要！！）
        //     当前代码没有设置缓存过期时间 → 数据会永久存在 Redis，除非手动删除或重启

        stringRedisTemplate.expire(key, 30, TimeUnit.DAYS);

        // 11. 返回数据库查询结果给前端
        return Result.ok(typeList);
    }
}
