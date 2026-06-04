-- confirm_reservation.lua
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

if existing_status == 'CONFIRMED' then
    return cjson.encode({
        status = "SUCCESS",
        code = "IDEMPOTENT_CONFIRM",
        message = "Reservation already confirmed",
        available = available,
        reserved = reserved,
        total = total
    })
end

if existing_status == 'CANCELLED' then
    return cjson.encode({
        status = "ERROR",
        code = "RESERVATION_CANCELLED",
        message = "Cannot confirm a cancelled reservation"
    })
end

if existing_status ~= 'RESERVED' then
    return cjson.encode({
        status = "ERROR",
        code = "RESERVATION_NOT_RESERVED",
        message = "Reservation is not in RESERVED status"
    })
end

if reserved < quantity then
    return cjson.encode({
        status = "ERROR",
        code = "INSUFFICIENT_RESERVED",
        message = "Not enough reserved stock to confirm"
    })
end

redis.call('HINCRBY', key, 'reserved', -quantity)
redis.call('HSET', reservation_key, 'status', 'CONFIRMED')

local final_reserved = tonumber(redis.call('HGET', key, 'reserved') or 0)
return cjson.encode({
    status = "SUCCESS",
    code = "CONFIRMED",
    message = "Reservation confirmed successfully",
    available = available,
    reserved = final_reserved,
    total = total
})
