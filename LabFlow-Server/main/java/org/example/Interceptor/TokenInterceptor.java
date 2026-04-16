//package org.example.Interceptor;
//
//import io.jsonwebtoken.Claims;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import lombok.extern.slf4j.Slf4j;
//import org.example.utils.JwtUtils;
//import org.example.utils.ThreadLocalUtil;
//import org.jspecify.annotations.Nullable;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.data.redis.core.ValueOperations;
//import org.springframework.stereotype.Component;
//import org.springframework.web.servlet.HandlerInterceptor;
//
//@Slf4j
//@Component
//public class TokenInterceptor implements HandlerInterceptor {
//
//    @Autowired
//    private StringRedisTemplate stringRedisTemplate;
//
//    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//
//        // 【核心修复 1】：无条件放行跨域预检请求 (OPTIONS)
//        // 浏览器在发送跨域带请求头的真实请求前，会先发一个 OPTIONS 探路，这个探路请求是不带 Token 的
//        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
//            return true;
//        }
//
//        // 获取请求头中的 Authorization 字段
//        String token = request.getHeader("Authorization");
//
//        // 【核心修复 2】：规范处理 Bearer 前缀
//        // 前端传过来的是 "Bearer eyJhb..."，我们必须把前 7 个字符切掉，拿到真正纯净的 Token
//        if (token != null && token.startsWith("Bearer ")) {
//            token = token.substring(7);
//        }
//
//        // 判断纯净的 token 是否存在
//        if(token == null || token.isEmpty()){
//            log.info("未登录或请求头格式错误，令牌为空");
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401 未认证
//            return false;
//        }
//
//        // 解析并校验 Token
//        try {
//            // 1. 先去 Redis 中校验该 Token 是否已失效 (比如用户已退出登录)
//            ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
//            String redisToken = operations.get(token); // 此时用纯净的 token 去查就对了
//            if (redisToken == null){
//                log.info("Redis中找不到该Token，可能已过期或主动注销");
//                throw new RuntimeException("Redis token expired");
//            }
//
//            // 2. 解析 JWT 载荷
//            Claims claims = JwtUtils.parseJWT(token);
//
//            // 3. 将解析出的用户信息存入当前线程上下文
//            ThreadLocalUtil.set(claims);
//            log.info("令牌校验通过，放行请求");
//
//        } catch (Exception e){
//            log.info("令牌非法或已过期: {}", e.getMessage());
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return false;
//        }
//
//        // 校验全部通过，放行到达 Controller
//        return true;
//    }
//
//    @Override
//    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
//        // 请求处理完毕后，必须清空 ThreadLocal，防止内存泄漏和线程池数据串用
//        ThreadLocalUtil.remove();
//    }
//}

package org.example.Interceptor;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.utils.JwtUtils;
import org.example.utils.ThreadLocalUtil;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class TokenInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        String uri = request.getRequestURI();
        String method = request.getMethod();

        // 1. 无条件放行跨域预检请求 (OPTIONS)
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        // 2. 【核心新增】：定义公开 / 半公开接口规则
        // 凡是 GET 请求读取 notes 列表、详情，以及 topics 列表的，都允许游客访问
        boolean isPublicApi = ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/v1/notes")) ||
                ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/v1/topics"));

        // 获取 Token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 3. 游客模式处理逻辑
        if(token == null || token.isEmpty()){
            if (isPublicApi) {
                log.info("游客访问公开接口: {}", uri);
                return true; // 没带Token，但访问的是公开接口，放行！
            } else {
                log.info("未登录拦截: {}", uri);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false; // 必须登录的接口，拦截！
            }
        }

        // 4. 用户模式逻辑 (带了Token，尝试解析)
        try {
            ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
            String redisToken = operations.get(token);
            if (redisToken == null){
                throw new RuntimeException("Redis token expired");
            }

            Claims claims = JwtUtils.parseJWT(token);
            ThreadLocalUtil.set(claims); // 存入线程池，Service 层可以通过它拿到 userId

        } catch (Exception e){
            // 细节：如果 Token 过期了，但访问的是公开接口，我们将其“降级”为游客，不要直接报错
            if (isPublicApi) {
                log.info("Token已失效，降级为游客访问公开接口: {}", uri);
                return true;
            }
            log.info("Token非法或已过期，拦截: {}", uri);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        ThreadLocalUtil.remove();
    }
}