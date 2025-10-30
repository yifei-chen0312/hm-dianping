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

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //这里按ctrl+i就可以自动构建这两个方法
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 获取session
        //HttpSession session = request.getSession();
        // TODO 获取请求头中的token
        String token = request.getHeader("token");
        if(StrUtil.isBlank(token)){

            return true;
        }
        String tokenkey = LOGIN_USER_KEY+token;
        //2. 获取session中的用户信息
        //UserDTO user = (UserDTO) session.getAttribute("user");
        Map<Object, Object> usermap = stringRedisTemplate.opsForHash().entries(token);
        //3. 判断用户是否存在
        if (usermap.isEmpty()) {
            //4. 不存在，则拦截

            return true;
        }
        //将map转为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);
        //5. 存在，保存用户信息到ThreadLocal，UserHolder是提供好了的工具类
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(tokenkey,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
