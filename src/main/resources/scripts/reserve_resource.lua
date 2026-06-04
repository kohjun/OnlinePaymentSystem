-- reserve_resource.lua
local key = KEYS[1]
local quantity = tonumber(ARGV[1])
local reservation_id = ARGV[2]
local ttl = tonumber(ARGV[3])
local now = ARGV[4] or ''
local reservation_key = 'reservation:' .. reservation_id

local available = tonumber(redis.call('HGET', key, 'available') or 0)
local reserved = tonumber(redis.call('HGET', key, 'reserved') or 0)
local total = tonumber(redis.call('HGET', key, 'total') or 0)
local existing_status = redis.call('HGET', reservation_key, 'status')

if quantity <= 0 then
    return cjson.encode({
        status = "ERROR",
        code = "INVALID_QUANTITY",
        message = "Quantity must be positive"
    })
end

if existing_status == 'RESERVED' or existing_status == 'CONFIRMED' then
    return cjson.encode({
        status = "SUCCESS",
        code = "IDEMPOTENT_RESERVE",
        message = "Reservation already exists",
        available = available,
        reserved = reserved,
        total = total
    })
end

if existing_status == 'CANCELLED' then
    return cjson.encode({
        status = "ERROR",
        code = "RESERVATION_CANCELLED",
        message = "Reservation was already cancelled"
    })
end

if available < quantity then
    return cjson.encode({
        status = "ERROR",
        code = "INSUFFICIENT_STOCK",
        message = "Not enough available stock"
    })
end

redis.call('HINCRBY', key, 'available', -quantity)
redis.call('HINCRBY', key, 'reserved', quantity)

if reservation_id and reservation_id ~= '' then
    redis.call('HSET', reservation_key, 'product_id', key)
    redis.call('HSET', reservation_key, 'quantity', quantity)
    redis.call('HSET', reservation_key, 'status', 'RESERVED')
    redis.call('HSET', reservation_key, 'created_at', now)

    if ttl > 0 then
        redis.call('EXPIRE', reservation_key, ttl)
    end
end

return cjson.encode({
    status = "SUCCESS",
    code = "RESERVED",
    message = "Resource reserved successfully",
    available = available - quantity,
    reserved = reserved + quantity,
    total = total
})
