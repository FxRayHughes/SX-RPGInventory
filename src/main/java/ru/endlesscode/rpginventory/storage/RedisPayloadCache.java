package ru.endlesscode.rpginventory.storage;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * SQL 的可选 Redis 二进制缓存，不保存租约或权威记录。断连时短暂熔断，调用者仍以 SQL 为准。
 * 独立版本键允许提交后的缓存写入乱序完成；TTL 只清理副本，绝不删除 SQL 或旧 Redis 持久化键。
 */
public final class RedisPayloadCache implements InventoryPayloadCache {
    // 与旧 sx-rpginventory:<namespace>:{record} 永久键完全分离；修改封装格式时必须升级此前缀。
    private static final String PREFIX = "sx-rpginventory:cache:v1:";
    private static final int MAGIC = 0x53584331;
    // 魔数、SQL 版本、长度及 SHA-256 校验和保证截断或错误版本的副本不会进入物品解码器。
    private static final int HEADER_SIZE = 4 + 8 + 4 + 32;
    private static final Logger LOGGER = Logger.getLogger("SX-RPGInventory");
    private final JedisPooled client;
    private final String prefix;
    private final int ttlSeconds;
    private final AtomicLong retryAfter = new AtomicLong(System.nanoTime());
    private final AtomicLong nextWarning = new AtomicLong(System.nanoTime());

    /**
     * 数据库完整身份仅以摘要出现在 Redis 键中，日志不输出含凭据的 URI。
     * 超时限制为 1..1000ms、TTL 为 1..86400s；构造不连接 Redis，缓存故障不阻止 SQL 启动。
     */
    public RedisPayloadCache(URI uri, String databaseIdentity, String namespace, int ttlSeconds, int timeoutMillis) {
        if (!"redis".equals(uri.getScheme()) && !"rediss".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Redis 缓存 URI 必须使用 redis 或 rediss");
        }
        if (!namespace.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Redis 缓存 namespace 无效");
        if (ttlSeconds < 1 || ttlSeconds > 86400 || timeoutMillis < 1 || timeoutMillis > 1000) {
            throw new IllegalArgumentException("Redis 缓存 TTL 必须为 1..86400 秒，超时必须为 1..1000 毫秒");
        }
        this.ttlSeconds = ttlSeconds;
        this.prefix = PREFIX + hex(digest(databaseIdentity.getBytes(StandardCharsets.UTF_8))) + ":" + namespace + ":";
        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(4);
        poolConfig.setMaxIdle(4);
        // 缓存池耗尽直接视为 miss，不能让额外的连接池等待阻塞 SQL 保存队列。
        poolConfig.setBlockWhenExhausted(false);
        client = new JedisPooled(poolConfig, uri, timeoutMillis, timeoutMillis);
    }

    @Override
    public byte[] get(StorageKey key, long revision) {
        if (unavailable()) return null;
        try {
            byte[] bytes = client.get(cacheKey(key, revision));
            if (bytes == null || bytes.length < HEADER_SIZE) return null;
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (buffer.getInt() != MAGIC || buffer.getLong() != revision || buffer.getInt() != bytes.length - HEADER_SIZE) return null;
            byte[] checksum = new byte[32];
            buffer.get(checksum);
            byte[] payload = Arrays.copyOfRange(bytes, HEADER_SIZE, bytes.length);
            return MessageDigest.isEqual(checksum, digest(payload)) ? payload : null;
        } catch (RuntimeException failure) {
            failed();
            return null;
        }
    }

    @Override
    public void put(StorageKey key, long revision, byte[] payload) {
        if (unavailable() || payload == null) return;
        try {
            ByteBuffer bytes = ByteBuffer.allocate(HEADER_SIZE + payload.length);
            bytes.putInt(MAGIC).putLong(revision).putInt(payload.length).put(digest(payload)).put(payload);
            // SETEX 原子写入内容与 TTL，不会留下永不过期的半成品缓存。
            client.setex(cacheKey(key, revision), ttlSeconds, bytes.array());
        } catch (RuntimeException failure) {
            failed();
        }
    }

    @Override
    public void close() {
        try { client.close(); } catch (RuntimeException failure) { failed(); }
    }

    private byte[] cacheKey(StorageKey key, long revision) {
        return (prefix + key.value() + ":" + revision).getBytes(StandardCharsets.UTF_8);
    }

    private boolean unavailable() { return System.nanoTime() < retryAfter.get(); }

    /** 单调时钟避免系统校时破坏熔断；日志不携带驱动异常，防止异常中的 URI 泄露凭据。 */
    private void failed() {
        long now = System.nanoTime();
        retryAfter.set(now + TimeUnit.SECONDS.toNanos(5));
        long previous = nextWarning.get();
        if (now >= previous && nextWarning.compareAndSet(previous, now + TimeUnit.SECONDS.toNanos(60))) {
            LOGGER.warning("Redis 装备缓存暂不可用，5 秒内直接读取 SQL；数据库中的装备数据不受影响。");
        }
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("运行环境缺少 SHA-256", impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
