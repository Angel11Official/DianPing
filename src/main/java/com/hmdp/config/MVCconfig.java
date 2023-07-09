package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.ReflushTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MVCconfig extends WebMvcConfigurationSupport {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/user/code",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**" ,
                        "/upload/**",
                        "/voucher/**"
                ).order(1);
        //token刷新拦截器
        //默认拦截所有请求 reflush需要先执行
        registry.addInterceptor(new ReflushTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);

    }
}
