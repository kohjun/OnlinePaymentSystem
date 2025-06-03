-- KEYS[1]: 예약 키 (e.g. "reservation:12345")

-- 예약 정보 조회
local reservation = redis.call('HGETALL', KEYS[1])
if #reservation == 0 then
    return false -- 예약이 존재하지 않음
end

-- 예약 정보 파싱
local resourceKey = nil
local quantity = 0
local status = nil

for i = 1, #reservation, 2 do
    if reservation[i] == "resource_key" then
        resourceKey = reservation[i+1]
    elseif reservation[i] == "quantity" then
        quantity = tonumber(reservation[i+1]) or 0
    elseif reservation[i] == "status" then
        status = reservation[i+1]
    end
end

-- 상태 확인
if status == "CANCELLED" then
    return true -- 이미 취소됨
end

if status == "CONFIRMED" then
    return false -- 이미 확정되어 취소 불가
end

if status ~= "RESERVED" then
    return false -- 예약 상태가 아님
end

-- 상태 업데이트
redis.call('HSET', KEYS[1], 'status', 'CANCELLED')

-- 예약된 수량 반환
if resourceKey then
    redis.call('HINCRBY', resourceKey, 'reserved', -quantity)
end

-- 성공
return true