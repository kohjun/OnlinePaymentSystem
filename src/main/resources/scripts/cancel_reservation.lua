-- cancel_reservation.lua
local key = KEYS[1]
local quantity = tonumber(ARGV[1])
local reservation_id = ARGV[2]
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

if existing_status == 'CANCELLED' then
    return cjson.encode({
        status = "SUCCESS",
        code = "IDEMPOTENT_CANCEL",
        message = "Reservation already cancelled",
        available = available,
        reserved = reserved,
        total = total
    })
end

if existing_status == 'RESERVED' then
    redis.call('HINCRBY', key, 'available', quantity)
    redis.call('HINCRBY', key, 'reserved', -quantity)
elseif existing_status == 'CONFIRMED' then
    redis.call('HINCRBY', key, 'available', quantity)
elseif existing_status == false then
    return cjson.encode({
        status = "ERROR",
        code = "RESERVATION_NOT_FOUND",
        message = "Reservation was not found"
    })
else
    return cjson.encode({
        status = "ERROR",
        code = "INVALID_RESERVATION_STATUS",
        message = "Reservation cannot be cancelled from current status"
    })
end

redis.call('HSET', reservation_key, 'status', 'CANCELLED')

local final_available = tonumber(redis.call('HGET', key, 'available') or 0)
local final_reserved = tonumber(redis.call('HGET', key, 'reserved') or 0)
return cjson.encode({
    status = "SUCCESS",
    code = "CANCELLED",
    message = "Reservation cancelled/rolled back successfully",
    available = final_available,
    reserved = final_reserved,
    total = total
})
