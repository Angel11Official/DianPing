--参数列表
--优惠券ID
local voucherId = ARGV[1]
--用户ID
local userId = ARGV[2]
--订单ID
local orderId = ARGV[3]

--数据key
--库存KEY
local stockKey = 'seckill:stock:'..voucherId
--订单ID
local orderKey = 'seckill:order:'..voucherId


--判断库存是否充足
--ge stockKey
if(tonumber(redis.call('get',stockKey))<=0)then
   --库存不足 返回1
    return 1
end

--判断用户是否下单
if(redis.call('sismember',orderKey,userId) == 1)then
    --下过单 返回2
    return 2
end

--库存充足&&没下单
--扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
--保存用户(下单） sadd orderKey userId
redis.call('sadd',orderKey,userId)
--发送消息到队列中 XADD stream.orders *(消息ID) k1 v1 k2 v2 k3 v3
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0