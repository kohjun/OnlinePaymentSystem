-- reserve_resource.lua
local key = KEYS[1]
local quantity = tonumber(ARGV[1])
local reservation_id = ARGV[2]
local ttl = tonumber(ARGV[3])

-- 재고 정보 가져오기
local available = tonumber(redis.call('HGET', key, 'available') or 0)
local reserved = tonumber(redis.call('HGET', key, 'reserved') or 0)
local total = tonumber(redis.call('HGET', key, 'total') or 0)

-- 유효성 검사
if quantity <= 0 then
    return cjson.encode({
        status = "ERROR",
        code = "INVALID_QUANTITY",
        message = "Quantity must be positive"
    })
end

if available < quantity then
    return cjson.encode({
        status = "ERROR",
        code = "INSUFFICIENT_STOCK",
        message = "Not enough available stock"
    })
end

-- 재고 업데이트
redis.call('HINCRBY', key, 'available', -quantity)
redis.call('HINCRBY', key, 'reserved', quantity)

-- 예약 정보 저장 (reservation_id가 있는 경우)
if reservation_id and reservation_id ~= '' then
    local reservation_key = 'reservation:' .. reservation_id
    redis.call('HSET', reservation_key, 'product_id', key)
    redis.call('HSET', reservation_key, 'quantity', quantity)
    redis.call('HSET', reservation_key, 'status', 'RESERVED')
    redis.call('HSET', reservation_key, 'created_at', ARGV[4] or '')

    if ttl > 0 then
        redis.call('EXPIRE', reservation_key, ttl)
    end
end

-- 성공 응답
return cjson.encode({
    status = "SUCCESS",
    code = "RESERVED",
    message = "Resource reserved successfully",
    available = available - quantity,
    reserved = reserved + quantity,
    total = total
})