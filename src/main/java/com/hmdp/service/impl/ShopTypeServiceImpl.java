package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private RedisTemplate redisTemplate;
    @Override
    public List<ShopType> getShopTypeList() {
        //返回商铺类型

        //1.从缓存中查找
        //SET :redisTemplate.opsForSet().members("CACHE_SHOP_TYPE");
        //LIST
        List<String>shopTypeStringList = redisTemplate.opsForList().range("CACHE_SHOP_TYPE",0,-1);
        //2.存在 直接返回
        if(shopTypeStringList.size()!=0){
            List<ShopType>shopTypesList = new ArrayList<>();
            for(String str:shopTypeStringList){
                ShopType shopType = JSONUtil.toBean(str,ShopType.class);
                shopTypesList.add(shopType);
            }
            return shopTypesList;
        }
        //3.不存在 从数据库中查找
        List<ShopType>list  = this.list();
        //4.不存在 返回错误
        if(list == null){
            return list;
        }
        //5.存在 写入redis
        for(ShopType shopType:list){
            redisTemplate.opsForList().rightPush("CACHE_SHOP_TYPE",JSONUtil.toJsonStr(shopType));
        }
        //6.返回
        return list;
    }
}
