package ru.endlesscode.rpginventory.storage;

import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 启动时验证权威 SQL 后端与可选缓存配置，不能把旧 Redis 持久化数据静默切换成空 SQL 库。 */
public final class RepositoryFactory {
    private RepositoryFactory() { }

    /** 玩家装备和背包共同使用选定的 SQL 后端；Redis 只能缓存已提交的物品快照。 */
    public static InventoryRepository open(ConfigurationSection config, Path dataDirectory) {
        String namespace = config.getString("storage.namespace", "default");
        if (namespace == null || !namespace.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("storage.namespace must contain 1..64 letters, digits, underscores or hyphens");
        }
        String backend = config.getString("storage.backend", "SQLITE").toUpperCase(Locale.ROOT);
        switch (backend) {
            case "SQLITE": {
                Path root = dataDirectory.toAbsolutePath().normalize();
                Path database = root.resolve(config.getString("storage.sqlite.file", "storage/inventories.db")).normalize();
                if (!database.startsWith(root)) throw new IllegalArgumentException("SQLite path must remain inside the data folder");
                try { Files.createDirectories(database.getParent()); }
                catch (IOException e) { throw new StorageException("Cannot create SQLite storage directory", e); }
                return openJdbc(config, "jdbc:sqlite:" + database, "", "", namespace, 1);
            }
            case "POSTGRESQL":
            case "POSTGRES":
                return openJdbc(config,
                    config.getString("storage.postgresql.url", "jdbc:postgresql://localhost:5432/minecraft"),
                    config.getString("storage.postgresql.username", "minecraft"),
                    secret(config, "storage.postgresql.password", "storage.postgresql.password-env"), namespace,
                    config.getInt("storage.postgresql.pool-size", 4));
            case "MYSQL":
                // 缓存只复制 SQL 已提交快照，不形成第二个权威写入目标。
                return openJdbc(config,
                    config.getString("storage.mysql.url", "jdbc:mysql://localhost:3306/minecraft"),
                    config.getString("storage.mysql.username", "minecraft"),
                    secret(config, "storage.mysql.password", "storage.mysql.password-env"), namespace,
                    config.getInt("storage.mysql.pool-size", 4));
            case "REDIS":
                throw new IllegalArgumentException("Redis 已不再支持作为持久化后端。请先将旧 Redis 装备数据迁移到 SQLite、MySQL 或 PostgreSQL，"
                        + "再修改 storage.backend；Redis 只能通过 storage.redis.enabled 开启为 SQL 缓存。旧 Redis 数据不会自动删除或迁移。");
            default: throw new IllegalArgumentException("Unknown storage backend: " + backend);
        }
    }

    /** JDBC 地址和账号共同隔离缓存；PG 不同账号可能使用不同默认 schema，SQLite 使用规范化绝对路径。 */
    private static InventoryRepository openJdbc(ConfigurationSection config, String url, String username, String password,
                                                String namespace, int poolSize) {
        InventoryPayloadCache cache = null;
        if (config.getBoolean("storage.redis.enabled", false)) {
            String cacheAddress = secret(config, "storage.redis.uri", "storage.redis.uri-env");
            URI cacheUri;
            try {
                cacheUri = URI.create(cacheAddress);
            } catch (IllegalArgumentException invalidAddress) {
                // URI 解析异常会带上含密码的原始输入，启动日志不得保留该异常及其 cause。
                throw new IllegalArgumentException("storage.redis.uri 格式无效，请检查 Redis 缓存连接地址。");
            }
            try {
                cache = new RedisPayloadCache(cacheUri,
                        url + "\nuser=" + username, namespace, config.getInt("storage.redis.ttl-seconds", 300),
                        config.getInt("storage.redis.timeout-millis", 200));
            } catch (IllegalArgumentException invalidCacheConfig) {
                // Jedis 还会解析 URI 路径与查询参数；未转义的密码可能混入这些字段，不能打印其异常链。
                throw new IllegalArgumentException("Redis 缓存配置无效，请检查连接地址、TTL（1..86400 秒）和超时（1..1000 毫秒）。");
            }
        }
        try {
            return new JdbcInventoryRepository(url, username, password, namespace, poolSize, cache);
        } catch (RuntimeException failure) {
            if (cache != null) cache.close();
            throw failure;
        }
    }

    /** Explicit environment references fail closed if absent, avoiding accidental unauthenticated connections. */
    private static String secret(ConfigurationSection config, String key, String environmentKey) {
        String variable = config.getString(environmentKey, "");
        if (isBlank(variable)) return config.getString(key, "");
        String value = System.getenv(variable);
        if (value == null || isBlank(value)) throw new IllegalArgumentException("Missing environment variable: " + variable);
        return value;
    }

    /** Match String.isBlank's Unicode whitespace rule without requiring Java 11 at runtime. */
    private static boolean isBlank(String value) {
        return value.codePoints().allMatch(Character::isWhitespace);
    }
}
