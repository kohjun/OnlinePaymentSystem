-- cancel_reservation.lua
local key = KEYS[1]
local quantity = tonumber(ARGV[1])
local reservation_id = ARGV[2]
local available = tonumber(redis.call('HGET', key, 'available') or 0)
local reserved = tonumber(redis.call('HGET', key, 'reserved') or 0)
local total = tonumber(redis.call('HGET', key, 'total') or 0)
if quantity <= 0 then
return cjson.encode({
status = "ERROR",
code = "INVALID_QUANTITY",
message = "Quantity must be positive"
})
end
if reserved < quantity then
redis.call('HINCRBY', key, 'available', quantity)
else
redis.call('HINCRBY', key, 'available', quantity)
redis.call('HINCRBY', key, 'reserved', -quantity)
end
if reservation_id and reservation_id ~= '' then
local reservation_key = 'reservation:' .. reservation_id
redis.call('HSET', reservation_key, 'status', 'CANCELLED')
end
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