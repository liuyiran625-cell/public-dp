package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenIntercepter implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenIntercepter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取请求头中的 token（前端每次请求都携带 authorization: token）
        String token = request.getHeader("authorization");
        // 2. 如果没有 token，直接放行（不刷新），后续由 LoginInterceptor 处理未登录逻辑
        if(StrUtil.isBlank(token)){
            return true;
        }
        // 3. 有 token → 拼接 Redis 中的 key
        String key = RedisConstants.LOGIN_USER_KEY + token;
        // 4. 从 Redis 查询该 token 对应的用户 Hash 数据
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 5. 判断用户是否存在（Hash 是否为空）
        if(userMap.isEmpty()){
            // 不存在 → token 无效或已过期 → 不刷新，直接放行（后面 LoginInterceptor 会返回 401）
            return true;
        }
        // 6. 存在 → 将 Hash 数据转为 UserDTO 对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 7. 把用户信息保存到当前线程的 ThreadLocal 中（供后续 LoginInterceptor 和业务代码使用）
        UserHolder.saveUser(userDTO);

        // 8. 刷新 token 的有效期：每次携带有效 token 的请求都会重新设置 30 分钟过期时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 9. 放行，无论是否有用户都放行（真正的登录校验交给后面的 LoginInterceptor）
        return true;


    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
