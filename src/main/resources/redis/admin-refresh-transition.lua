-- 관리자 Family와 sid는 같은 세션 식별자를 사용한다. 회전·재사용·폐기를 원자적으로 처리한다.
if redis.call('TYPE', KEYS[1]).ok ~= 'hash' then return 0 end
if redis.call('HGET', KEYS[1], 'subjectType') ~= 'ADMIN'
    or redis.call('HGET', KEYS[1], 'subjectId') ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'sessionId') ~= ARGV[2]
    or redis.call('HGET', KEYS[1], 'role') ~= ARGV[3] then return 0 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local absolute = tonumber(redis.call('HGET', KEYS[1], 'absoluteExpiresAtMillis'))
if not absolute or absolute <= now then return 0 end
local status = redis.call('HGET', KEYS[1], 'status')
if ARGV[4] == 'logout' or status == 'CONSUMED' then
    redis.call('DEL', KEYS[2])
    if status == 'CONSUMED' and ARGV[4] == 'rotate' then return -1 end
    return 2
end
if status ~= 'ACTIVE' or redis.call('TYPE', KEYS[2]).ok ~= 'hash'
    or redis.call('HGET', KEYS[2], 'subjectId') ~= ARGV[1]
    or redis.call('HGET', KEYS[2], 'role') ~= ARGV[3]
    or tonumber(redis.call('HGET', KEYS[1], 'idleExpiresAtMillis')) <= now then return 0 end
if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
local idle = math.min(tonumber(ARGV[6]), now + 3600000, absolute)
if idle <= now then return 0 end
redis.call('HSET', KEYS[1], 'status', 'CONSUMED', 'lastUsedAt', ARGV[5])
redis.call('PEXPIREAT', KEYS[1], absolute)
redis.call('HSET', KEYS[3], 'subjectType', 'ADMIN', 'subjectId', ARGV[1],
    'role', ARGV[3], 'sessionId', ARGV[2], 'familyId', ARGV[2],
    'status', 'ACTIVE', 'issuedAt', ARGV[5], 'lastUsedAt', ARGV[5],
    'familyCreatedAt', redis.call('HGET', KEYS[1], 'familyCreatedAt'),
    'idleExpiresAtMillis', idle, 'absoluteExpiresAtMillis', absolute, 'pepperVersion', ARGV[7])
redis.call('PEXPIREAT', KEYS[3], idle)
redis.call('HSET', KEYS[2], 'idleExpiresAtMillis', idle)
redis.call('PEXPIREAT', KEYS[2], idle)
return 1
