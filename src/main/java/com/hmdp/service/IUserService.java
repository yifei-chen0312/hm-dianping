package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.impl.UserServiceImpl;

import javax.servlet.http.HttpSession;


/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

//用户验证码获取
    Result sendCode(String phone, HttpSession session);
//用户登录功能
    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
