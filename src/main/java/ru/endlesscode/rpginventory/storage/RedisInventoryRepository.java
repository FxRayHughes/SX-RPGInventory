package ru.endlesscode.rpginventory.storage;

import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Redis can be selected as the authoritative backend, with atomic Lua fencing and persistent hashes.
 * Deployments must enable Redis persistence and noeviction; this implementation never treats Redis as
 * a disposable cache or automatically falls back to another database after an outage.
 */
public final class RedisInventoryRepository implements InventoryRepository {
    private final JedisPooled client;
    private final String prefix;
    private final String script;

    /** A redis/rediss URI supports authentication and TLS without exposing credentials in log messages. */
    public RedisInventoryRepository(URI uri, String namespace) {
        if (!"redis".equals(uri.getScheme()) && !"rediss".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Redis URI must use redis or rediss");
        }
        if (!namespace.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid storage namespace");
        prefix = "sx-rpginventory:" + namespace + ":";
        try (InputStream input = RedisInventoryRepository.class.getResourceAsStream("/storage/record.lua")) {
            if (input == null) throw new IOException("Missing Redis storage script");
            // Read the bundled protocol without requiring InputStream APIs introduced after Java 8.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            script = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot load Redis storage protocol", e);
        }
        client = new JedisPooled(uri);
        try {
            client.ping();
        } catch (RuntimeException failure) {
            client.close();
            throw new StorageException("Cannot connect to Redis inventory storage", failure);
        }
    }

    @Override
    public StoredRecord acquire(StorageKey key, String owner, long leaseMillis) {
        List<?> result = execute(key, "acquire", owner, leaseMillis, "0", "", "0");
        Object payload = result.get(2);
        try {
            return new StoredRecord(payload == null ? null : Base64.getDecoder().decode(payload.toString()),
                    Long.parseLong(result.get(1).toString()));
        } catch (IllegalArgumentException failure) {
            throw new StorageException("Invalid Redis inventory record: " + key.value(), failure);
        }
    }

    @Override
    public long save(StorageKey key, String owner, long revision, byte[] payload, long leaseMillis, boolean release) {
        Objects.requireNonNull(payload, "payload");
        List<?> result = execute(key, "save", owner, leaseMillis, Long.toString(revision),
                Base64.getEncoder().encodeToString(payload), release ? "1" : "0");
        return Long.parseLong(result.get(1).toString());
    }

    @Override
    public void renew(StorageKey key, String owner, long leaseMillis) {
        execute(key, "renew", owner, leaseMillis, "0", "", "0");
    }

    @Override
    public void release(StorageKey key, String owner) {
        execute(key, "release", owner, 1000, "0", "", "0");
    }

    @Override
    public boolean hasData(StorageKey key) {
        try {
            return client.hexists(redisKey(key), "payload");
        } catch (RuntimeException e) {
            throw new StorageException("Cannot inspect " + key.value(), e);
        }
    }

    @Override
    public void close() { client.close(); }

    private List<?> execute(StorageKey key, String op, String owner, long leaseMillis,
                            String revision, String payload, String release) {
        if (owner == null || owner.isEmpty() || owner.length() > 64 || leaseMillis < 1000) {
            throw new IllegalArgumentException("Invalid inventory ownership lease");
        }
        final Object response;
        try {
            response = client.eval(script, Collections.singletonList(redisKey(key)),
                    Arrays.asList(op, owner, Long.toString(leaseMillis), revision, payload, release));
        } catch (RuntimeException e) {
            throw new StorageException("Redis inventory operation failed: " + op + " " + key.value(), e);
        }
        if (!(response instanceof List<?>) || ((List<?>) response).isEmpty()) {
            throw new StorageException("Invalid Redis storage response");
        }
        List<?> result = (List<?>) response;
        if ("conflict".equals(result.get(0))) throw new StorageConflictException(key);
        if (!"ok".equals(result.get(0))) throw new StorageException("Unexpected Redis storage status");
        return result;
    }

    /** Hash-tagged record identity keeps all future per-record Lua keys in the same Redis cluster slot. */
    private String redisKey(StorageKey key) { return prefix + "{" + key.value() + "}"; }
}
