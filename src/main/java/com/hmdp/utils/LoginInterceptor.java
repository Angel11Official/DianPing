package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;

import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class LoginInterceptor  implements HandlerInterceptor {

//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
//        this.stringRedisTemplate = stringRedisTemplate;
//    }
    //前置拦截器
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取Session
//        HttpSession session = request.getSession();
//        //2.获取Session中的用户
//        Object user = session.getAttribute("user");
//        //3.判断用户是否存在
//        if(user == null){
//            //4.不存在，拦截 返回401状态码 （未授权）
//            response.setStatus(401);
//            return false;
//        }
//        UserDTO userDTO = new UserDTO();
//        BeanUtils.copyProperties(user,userDTO);
//        //5.存在 保存用户到ThreadLocal
//        UserHolder.saveUser(userDTO);
//        //6.放行
//        return true;

//        //1.获取请求头中的token
//        String token = request.getHeader("authorization");
//        if(StrUtil.isBlank(token)){
//            //4.不存在，拦截 返回401状态码 （未授权）
//            response.setStatus(401);
//            return false;
//        }
//        //2.基于token获取redis中的用户
//        Map<Object,Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
//        //3.判断用户是否存在
//        if(userMap.isEmpty()){
//            //4.不存在，拦截 返回401状态码 （未授权）
//            response.setStatus(401);
//            return false;
//        }
//        //5.将查询到的HASH转换为UserDTO对象
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
//        //6.存放到UserHolder中
//        UserHolder.saveUser(userDTO);
//        //7.刷新token的有效期
//        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//        //8.返回
//        return true;


        //1.判断是否需要拦截 依据是ThreadLocal Userholder中是否有用户
        if(UserHolder.getUser() == null){
            //需要拦截
            response.setStatus(401);
            return false;
        }
        //2.有用户 放行
        return  true;
    }


    //后置拦截器 移除用户 避免内存泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
