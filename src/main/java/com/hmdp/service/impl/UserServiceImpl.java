package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.server.Session;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.rmi.CORBA.Util;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.RandomAccess;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1检验手机号
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        //判断手机号是否正确
        if (phoneInvalid) {
            return Result.fail("手机号格式不正确");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //将验证码存入session
        //session.setAttribute("code", code);

        //改用redis代替session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL,TimeUnit.MINUTES);

        //发送验证码
        log.debug("验证码为:{}", code);

        return Result.ok();
    }

    //用户登录
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.判断手机号是否正确
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }

        //2.判断验证码是否正确(根据手机号查验证码
        String code = loginForm.getCode();
        //Object cachecode = session.getAttribute("code");
        //改用redis代替session
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code == null || !cachecode.equals(code)) {
            //验证码错误
            return Result.fail("验证码错误");
        }
        //3.判断手机号是否存在
        User user = query().eq("phone", phone).one();


        if (user == null) {
            //手机号不存在创建新用户
            user = CreateUser(phone);
        }
        //手机号存在，把用户保存到session
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //生成随机令牌
        String token = UUID.randomUUID().toString(true);
        //把user对象转为hash对象存入redis里
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //转变为map存入redis
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        //存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, stringObjectMap);

        //设置对象的存活周期
        String tokenkey = LOGIN_USER_KEY + token;
        stringRedisTemplate.expire(tokenkey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    private User CreateUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(5));
        save(user);
        return user;

    }
}
