package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    //获取验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号 正则表达式
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合 返回错误信息
            return Result.fail("手机号格式不正确！");
        }
        //3.符合 生成验证码
        String code = RandomUtil.randomNumbers(4);
        //4.保存在Session中
        //session.setAttribute("code",code);
        //4.保存在Redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码 返回结果
        log.debug("发送验证码成功:{}",stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone));
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号 正则表达式
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //如果不符合 返回错误信息
            return Result.fail("手机号格式不正确！");
        }

        //2.从Session获取验证码，校验验证码
        //Object cacheCode = session.getAttribute("code");
        //String code = loginForm.getCode();
        //if(cacheCode == null || !cacheCode.toString().equals(code)){
        //    //3.不一致 报错
        //     //log.debug(cacheCode.toString());
            //return Result.fail("验证码错误！");
        //}

        //2.从Redis中获取验证码 校验
        Object cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            //3.不一致 报错
            return Result.fail("验证码错误！");
        }

        //4.一致 根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone",phone).one();

        //5.不存在 创建新用户 保存到数据库
        if(user == null){
             user = createUserWithPhone(phone);
        }

        //6.无论是否存在 都需要保存用户信息到Session
        //session.setAttribute("user",user);

        //6.无论是否存在 都需要保存用户信息到Redis
        // 随机生成一个token 作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 将User对象转为Hash存储
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->
                            fieldValue.toString()
                        ));
        // 存入Redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        //设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //7.返回结果
        //return Result.ok(user);
        return Result.ok(token);
    }


    //签到
    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前时间 年？月？日？
        LocalDateTime now = LocalDateTime.now();
        //3.拼接KEY
        String key = "sign:" + userId + now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //4.根据“日”得到今天是本月的第几天 这里要-1
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    //统计本月连续签到
    @Override
    public Result signCount() {
        //获取本月截止今天为止的所有签到记录--要找到key--要找到用户id 日期
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前时间 年？月？日？
        LocalDateTime now = LocalDateTime.now();
        //3.拼接KEY
        String key = "sign:" + userId + now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        //4.根据“日”得到今天是本月的第几天 这里要-1
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止的所有签到记录 返回的应该是一个十进制数字
        // bitfield sign:userid:yyyyMM u[dayOfMonth] 0
        List<Long> ret = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        //非空判断
        if(ret == null|| ret.isEmpty()){
            return Result.ok(0);
        }
        //返回的是一个非空链表 那么需要的值 一定下标是0
        Long num = ret.get(0);
        if(num == null || num ==0){
            return Result.ok(0);
        }
        //6.与运算
        int cnt = 0;
        while((num&1)==1){
            ++cnt;
            num >>>= 1;
        }
        return Result.ok(cnt);
    }




    //根据手机号创建用户
    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //保存用户
        this.save(user);
        //返回用户
        return user;
    }
}
