-- One key per inventory keeps every operation atomic, including fencing and lease release.
-- Payloads never get a TTL: ownership expiration must not delete equipment or backpacks.
local key = KEYS[1]
local op = ARGV[1]
local owner = ARGV[2]
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local currentOwner = redis.call('HGET', key, 'owner')
local expires = tonumber(redis.call('HGET', key, 'lease_until') or '0')
local revision = tonumber(redis.call('HGET', key, 'revision') or '0')

if op == 'acquire' then
    if currentOwner and currentOwner ~= owner and expires > now then return {'conflict'} end
    redis.call('HSET', key, 'owner', owner, 'lease_until', now + tonumber(ARGV[3]), 'revision', revision)
    return {'ok', tostring(revision), redis.call('HGET', key, 'payload') or false}
elseif op == 'save' then
    if currentOwner ~= owner or expires <= now or revision ~= tonumber(ARGV[4]) then return {'conflict'} end
    revision = revision + 1
    redis.call('HSET', key, 'payload', ARGV[5], 'revision', revision, 'updated_at', now)
    if ARGV[6] == '1' then
        redis.call('HDEL', key, 'owner')
        redis.call('HSET', key, 'lease_until', 0)
    else
        redis.call('HSET', key, 'lease_until', now + tonumber(ARGV[3]))
    end
    return {'ok', tostring(revision)}
elseif op == 'renew' then
    if currentOwner ~= owner or expires <= now then return {'conflict'} end
    redis.call('HSET', key, 'lease_until', now + tonumber(ARGV[3]))
    return {'ok'}
elseif op == 'release' then
    if currentOwner == owner then
        redis.call('HDEL', key, 'owner')
        redis.call('HSET', key, 'lease_until', 0)
    end
    return {'ok'}
end
return redis.error_reply('Unsupported inventory operation')
