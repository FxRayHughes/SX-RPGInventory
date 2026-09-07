package ru.endlesscode.rpginventory.storage;

import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Validates configuration before opening a backend; backend changes require a restart, never a silent fallback. */
public final class RepositoryFactory {
    private RepositoryFactory() { }

    /** The selected backend is authoritative for both player inventories and portable backpacks. */
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
                return new JdbcInventoryRepository("jdbc:sqlite:" + database, "", "", namespace, 1);
            }
            case "POSTGRESQL":
            case "POSTGRES":
                return new JdbcInventoryRepository(
                    config.getString("storage.postgresql.url", "jdbc:postgresql://localhost:5432/minecraft"),
                    config.getString("storage.postgresql.username", "minecraft"),
                    secret(config, "storage.postgresql.password", "storage.postgresql.password-env"), namespace,
                    config.getInt("storage.postgresql.pool-size", 4));
            case "MYSQL":
                // MySQL owns the same complete records as other backends; configuration never enables a dual write.
                return new JdbcInventoryRepository(
                    config.getString("storage.mysql.url", "jdbc:mysql://localhost:3306/minecraft"),
                    config.getString("storage.mysql.username", "minecraft"),
                    secret(config, "storage.mysql.password", "storage.mysql.password-env"), namespace,
                    config.getInt("storage.mysql.pool-size", 4));
            case "REDIS":
                return new RedisInventoryRepository(
                    URI.create(secret(config, "storage.redis.uri", "storage.redis.uri-env")), namespace);
            default: throw new IllegalArgumentException("Unknown storage backend: " + backend);
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
