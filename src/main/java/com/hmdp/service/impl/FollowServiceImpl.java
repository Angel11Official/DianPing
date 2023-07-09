package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    //关注 或者 取消
    @Override
    public Result follow(Long followUserId, boolean isFollow) {
        //获取登录用户
        Long userId= UserHolder.getUser().getId();
        //1.判断关注还是取关
        if(isFollow){
            //2.关注 新增记录
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            Boolean isSuccess = save(follow);
            if(isSuccess){
                //保存成功 将这条记录写入redis  sadd userid follow_user_id
                String key = "FOLLOWS"+userId;
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
                return Result.ok("关注成功！");
            }else return Result.fail("关注失败");
        }
        else{
            //3.取关 删除记录
            //delete * from follow where userId = ? and follow_user_id = ?
            LambdaQueryWrapper<Follow>queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId,userId)
                    .eq(Follow::getFollowUserId,followUserId);
            Boolean isSuccess = this.remove(queryWrapper);
            if(isSuccess){
                //删除成功 从redis中把这条记录删除  srmv userid follow_user_id
                String key = "FOLLOWS"+userId;
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
                return Result.ok("取消关注成功！");
            }else return Result.fail("取消关注失败");
        }
    }

    //返回登录用户是否关注了该用户
    @Override
    public Result isFollow(Long followUserId) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //查询数据库 select * from follow where userId = ? and follow_user_id = ? == null?
        LambdaQueryWrapper<Follow>queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getUserId,userId)
                .eq(Follow::getFollowUserId,followUserId);
        Follow follow = this.getOne(queryWrapper);
        if(follow == null){
            //没有关注
            return Result.fail("");
        }else return Result.ok("");
    }

    //获取共同关注
    @Override
    public Result common(Long goalUserId) {
        //1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 从REDIS中执行查询
        String key1 = "FOLLOWS"+userId;
        String key2 = "FOLLOWS"+goalUserId;
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key1,key2);
        //3.处理查询结果 返回
        if(set.isEmpty()){
            return Result.ok("无共同关注!");
        }
        List<UserDTO> list = new ArrayList<>();
        for(String id:set){
            User user = userService.getById(id);
            UserDTO userDTO = new UserDTO();
            BeanUtil.copyProperties(user,userDTO);
            list.add(userDTO);
        }
        return Result.ok(list);
    }

}
