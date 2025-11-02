-- KEYS[1]: 리소스 키 (e.g. "inventory:PROD-001")
-- ARGV[1]: 예약 ID (멱등성 키)
-- ARGV[2]: 예약 수량
-- ARGV[3]: TTL (초)

-- 리소스 정보 조회
local resource = redis.call('HGETALL', KEYS[1])
local available = 0
local reserved = 0
local total = 0

-- ✅ 수정: 해시 결과를 키-값 쌍으로 변환
for i = 1, #resource, 2 do
    if resource[i] == "available" then          -- ✅ "quantity" → "available"
        available = tonumber(resource[i+1]) or 0
    elseif resource[i] == "reserved" then
        reserved = tonumber(resource[i+1]) or 0
    elseif resource[i] == "total" then          -- ✅ 추가: total 필드
        total = tonumber(resource[i+1]) or 0
    end
end

-- 예약 수량
local requestedAmount = tonumber(ARGV[2])

-- ✅ 수정: nil 체크 추가
if not requestedAmount or requestedAmount <= 0 then
    return {false, "INVALID_QUANTITY"}
end

-- ✅ 수정: available 수량으로 직접 확인 (기존: quantity - reserved)
if available < requestedAmount then
    return {false, "INSUFFICIENT_QUANTITY"}
end

-- 예약 정보 키
local reservationKey = "reservation:" .. ARGV[1]

-- 이미 존재하는 예약 확인
local exists = redis.call('EXISTS', reservationKey)
if exists == 1 then
    local status = redis.call('HGET', reservationKey, "status")
    if status == "CONFIRMED" then
        return {true, "ALREADY_CONFIRMED"}
    elseif status == "CANCELLED" then
        return {false, "ALREADY_CANCELLED"}
    else
        return {true, "ALREADY_RESERVED"}
    end
end

-- ✅ 수정: available 감소, reserved 증가
redis.call('HINCRBY', KEYS[1], 'available', -requestedAmount)
redis.call('HINCRBY', KEYS[1], 'reserved', requestedAmount)

-- 예약 정보 저장
redis.call('HMSET', reservationKey,
    'resource_key', KEYS[1],
    'quantity', requestedAmount,
    'status', 'RESERVED',
    'timestamp', redis.call('TIME')[1]
)

-- 예약 TTL 설정
local ttl = tonumber(ARGV[3]) or 3600  -- ✅ 기본값 1시간
redis.call('EXPIRE', reservationKey, ttl)

-- 성공 응답
return {true, "RESERVED"}