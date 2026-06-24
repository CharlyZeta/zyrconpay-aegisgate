-- converge.lua
-- KEYS[1] : Redis set key for transaction (e.g., payment:3ds:events:[transaction_id])
-- ARGV[1] : Action/Token (PAYMENT_INTENT_CREATED, 3DS_WEBHOOK_RECEIVED:SUCCESS, 3DS_WEBHOOK_RECEIVED:FAILED, or AUTHORIZE_CHECK)
-- ARGV[2] : TTL in seconds (e.g., 600)

local hasIntent = redis.call('SISMEMBER', KEYS[1], 'PAYMENT_INTENT_CREATED')
local hasSuccess = redis.call('SISMEMBER', KEYS[1], '3DS_WEBHOOK_RECEIVED:SUCCESS')
local hasFailed = redis.call('SISMEMBER', KEYS[1], '3DS_WEBHOOK_RECEIVED:FAILED')

if ARGV[1] == 'AUTHORIZE_CHECK' then
    if hasIntent == 1 and hasSuccess == 1 then
        redis.call('DEL', KEYS[1])
        return 1 -- Authorized & consumed (success)
    elseif hasIntent == 1 and hasFailed == 1 then
        redis.call('DEL', KEYS[1])
        return -2 -- Verification failed, cannot authorize
    else
        return -1 -- Security bypass: missing/invalid tokens
    end
end

-- Normal token addition path
redis.call('SADD', KEYS[1], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[2])

hasIntent = redis.call('SISMEMBER', KEYS[1], 'PAYMENT_INTENT_CREATED')
hasSuccess = redis.call('SISMEMBER', KEYS[1], '3DS_WEBHOOK_RECEIVED:SUCCESS')
hasFailed = redis.call('SISMEMBER', KEYS[1], '3DS_WEBHOOK_RECEIVED:FAILED')

if hasIntent == 1 and hasSuccess == 1 then
    return 1 -- Converged success (do not delete yet, let status query read it)
elseif hasIntent == 1 and hasFailed == 1 then
    return 2 -- Converged failure (do not delete yet, let status query read it)
else
    return 0 -- Pending
end
