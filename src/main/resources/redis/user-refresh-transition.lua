-- 조회 이후 상태가 바뀌어도 회전·재사용 판정·폐기를 한 번에 처리한다.
if redis.call('TYPE', KEYS[1]).ok ~= 'hash' or redis.call('TYPE', KEYS[2]).ok ~= 'set' then return 0 end
if redis.call('HGET', KEYS[1], 'familyId') ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'subjectId') ~= ARGV[2]
    or redis.call('HGET', KEYS[1], 'subjectType') ~= 'USER'
    or redis.call('HGET', KEYS[1], 'absoluteExpiresAt') ~= ARGV[3]
    or redis.call('HGET', KEYS[1], 'idleExpiresAt') ~= ARGV[4] then return 0 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local absolute = tonumber(ARGV[5])
if absolute <= now then return 0 end
-- 회원 상태 변경의 전체 폐기 세대를 원자적으로 확인한다. 이전 저장 형식은 빈 세대다.
local generation = redis.call('GET', KEYS[5]) or ''
if (redis.call('HGET', KEYS[1], 'generation') or '') ~= generation then return 0 end
if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
local status = redis.call('HGET', KEYS[1], 'status')
if ARGV[6] == 'logout' or ARGV[6] == 'revoke' or status == 'CONSUMED' then
    redis.call('SET', KEYS[3], 'REVOKED', 'PXAT', absolute)
    if status == 'CONSUMED' and ARGV[6] == 'rotate' then return -1 end
    return 2
end
if status ~= 'ACTIVE' or tonumber(ARGV[7]) <= now then return 0 end
if redis.call('EXISTS', KEYS[4]) == 1 then return 0 end
local idle = math.min(now + tonumber(ARGV[8]), absolute)
redis.call('HSET', KEYS[1], 'status', 'CONSUMED', 'lastUsedAt', ARGV[9])
-- 소비 기록은 유휴 TTL 대신 절대 만료까지 남겨 후속 회전 뒤에도 재사용을 탐지한다.
redis.call('PEXPIREAT', KEYS[1], absolute)
redis.call('HSET', KEYS[4],
    'subjectType', 'USER', 'subjectId', ARGV[2],
    'sessionId', redis.call('HGET', KEYS[1], 'sessionId'), 'familyId', ARGV[1],
    'status', 'ACTIVE', 'issuedAt', ARGV[9], 'lastUsedAt', ARGV[9],
    'familyCreatedAt', redis.call('HGET', KEYS[1], 'familyCreatedAt'),
    'idleExpiresAt', ARGV[10], 'absoluteExpiresAt', ARGV[3],
    'pepperVersion', ARGV[11], 'generation', generation)
redis.call('PEXPIREAT', KEYS[4], math.min(tonumber(ARGV[12]), idle))
redis.call('SADD', KEYS[2], ARGV[13])
redis.call('PEXPIREAT', KEYS[2], absolute)
-- 이 세대를 참조하는 마지막 Family가 절대 만료할 때까지 표식을 보존한다.
if generation ~= '' and redis.call('PTTL', KEYS[5]) < absolute - now then
    redis.call('PEXPIREAT', KEYS[5], absolute)
end
return 1
