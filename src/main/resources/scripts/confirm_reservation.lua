-- confirm_reservation.lua
local key = KEYS[1]
local quantity = tonumber(ARGV[1])
local reservation_id = ARGV[2]

-- 재고 정보 가져오기
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

if reserved < quantity then
    return cjson.encode({
        status = "ERROR",
        code = "INSUFFICIENT_RESERVED",
        message = "Not enough reserved stock to confirm"
    })
end

-- 예약 재고를 차감만 (available은 이미 reserve 시점에 차감됨)
redis.call('HINCRBY', key, 'reserved', -quantity)

-- 예약 정보 업데이트
if reservation_id and reservation_id ~= '' then
    local reservation_key = 'reservation:' .. reservation_id
    redis.call('HSET', reservation_key, 'status', 'CONFIRMED')
end

-- 성공 응답
local available = tonumber(redis.call('HGET', key, 'available') or 0)
return cjson.encode({
    status = "SUCCESS",
    code = "CONFIRMED",
    message = "Reservation confirmed successfully",
    available = available,
    reserved = reserved - quantity,
    total = total
})