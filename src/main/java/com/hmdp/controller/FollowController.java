package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;


    //关注/取关
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId,@PathVariable("isFollow") boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }

    //获取是否关注
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);
    }

    //共同关注
    @GetMapping("/common/{id}")
    public Result common(@PathVariable("id")Long goalUserId){
        return followService.common(goalUserId);
    }
}
