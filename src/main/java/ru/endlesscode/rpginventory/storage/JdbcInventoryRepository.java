package ru.endlesscode.rpginventory.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * SQLite/PostgreSQL repository with pooled connections, database-clock leases and conditional writes.
 * The namespace partitions independent networks sharing one database without interpolating table names.
 */
public final class JdbcInventoryRepository implements InventoryRepository {
    private final HikariDataSource pool;
    private final String namespace;
    private final boolean sqlite;
    // Stable schema name and column meanings; migrations must preserve payload, revision and ownership together.
    private static final String TABLE = "sx_rpginventory_records";

    /** Create schema once; SQLite uses one WAL writer with a busy timeout rather than competing writers. */
    public JdbcInventoryRepository(String url, String username, String password, String namespace, int poolSize) {
        this.sqlite = url.startsWith("jdbc:sqlite:");
        if (!sqlite && !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Only SQLite and PostgreSQL JDBC URLs are supported");
        }
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        HikariConfig config = new HikariConfig();
        config.setPoolName("SX-RPGInventory");
        config.setJdbcUrl(url);
        config.setDriverClassName(sqlite ? "org.sqlite.JDBC" : "org.postgresql.Driver");
        config.setMaximumPoolSize(sqlite ? 1 : Math.max(1, poolSize));
        config.setConnectionTimeout(10000);
        if (sqlite) {
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "FULL");
            config.addDataSourceProperty("busy_timeout", "10000");
        } else {
            config.setUsername(username);
            config.setPassword(password);
            config.addDataSourceProperty("connectTimeout", "10");
            config.addDataSourceProperty("socketTimeout", "15");
        }
        pool = new HikariDataSource(config);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "namespace VARCHAR(128) NOT NULL, record_key VARCHAR(64) NOT NULL, "
                    + "payload " + (sqlite ? "BLOB" : "BYTEA") + ", revision BIGINT NOT NULL DEFAULT 0, "
                    + "owner VARCHAR(64), lease_until BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (namespace, record_key))");
        } catch (SQLException e) {
            pool.close();
            throw new StorageException("Cannot initialize inventory schema", e);
        }
    }

    @Override
    public StoredRecord acquire(StorageKey key, String owner, long leaseMillis) {
        validateLease(owner, leaseMillis);
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Legacy CraftBukkit exposes its own pre-3.24 SQLite driver to plugins. INSERT OR IGNORE
                // creates only an absent key without replacing an existing payload, revision or ownership lease.
                String insertSql = sqlite ? "INSERT OR IGNORE INTO " + TABLE + " (namespace, record_key) VALUES (?, ?)"
                        : "INSERT INTO " + TABLE + " (namespace, record_key) VALUES (?, ?) ON CONFLICT (namespace, record_key) DO NOTHING";
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    identify(insert, key, 1);
                    insert.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                        + " SET owner=?, lease_until=" + clock() + "+? WHERE namespace=? AND record_key=? AND (lease_until<=" + clock() + " OR owner=?)")) {
                    update.setString(1, owner);
                    update.setLong(2, leaseMillis);
                    identify(update, key, 3);
                    update.setString(5, owner);
                    if (update.executeUpdate() != 1) throw new StorageConflictException(key);
                }
                StoredRecord record;
                try (PreparedStatement select = connection.prepareStatement("SELECT payload, revision FROM " + TABLE
                        + " WHERE namespace=? AND record_key=?")) {
                    identify(select, key, 1);
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) throw new StorageException("Claimed record disappeared: " + key.value());
                        record = new StoredRecord(result.getBytes(1), result.getLong(2));
                    }
                }
                connection.commit();
                return record;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException e) {
            throw new StorageException("Cannot acquire " + key.value(), e);
        }
    }

    @Override
    public long save(StorageKey key, String owner, long revision, byte[] payload, long leaseMillis, boolean release) {
        validateLease(owner, leaseMillis);
        Objects.requireNonNull(payload, "payload");
        try (Connection connection = pool.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                    + " SET payload=?, revision=revision+1, owner=?, lease_until=CASE WHEN ?=1 THEN 0 ELSE " + clock() + "+? END, updated_at=" + clock()
                    + " WHERE namespace=? AND record_key=? AND owner=? AND revision=? AND lease_until>" + clock())) {
                update.setBytes(1, payload);
                update.setString(2, release ? null : owner);
                update.setInt(3, release ? 1 : 0);
                update.setLong(4, leaseMillis);
                identify(update, key, 5);
                update.setString(7, owner);
                update.setLong(8, revision);
                if (update.executeUpdate() != 1) throw new StorageConflictException(key);
                return revision + 1;
            }
        } catch (SQLException e) {
            throw new StorageException("Cannot save " + key.value(), e);
        }
    }

    @Override
    public void renew(StorageKey key, String owner, long leaseMillis) {
        validateLease(owner, leaseMillis);
        try (Connection connection = pool.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                    + " SET lease_until=" + clock() + "+? WHERE namespace=? AND record_key=? AND owner=? AND lease_until>" + clock())) {
                update.setLong(1, leaseMillis);
                identify(update, key, 2);
                update.setString(4, owner);
                if (update.executeUpdate() != 1) throw new StorageConflictException(key);
            }
        } catch (SQLException e) {
            throw new StorageException("Cannot renew " + key.value(), e);
        }
    }

    @Override
    public void release(StorageKey key, String owner) {
        try (Connection connection = pool.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE + " SET owner=NULL, lease_until=0 WHERE namespace=? AND record_key=? AND owner=?")) {
            identify(update, key, 1);
            update.setString(3, owner);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Cannot release " + key.value(), e);
        }
    }

    @Override
    public boolean hasData(StorageKey key) {
        try (Connection connection = pool.getConnection(); PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM " + TABLE + " WHERE namespace=? AND record_key=? AND payload IS NOT NULL")) {
            identify(select, key, 1);
            try (ResultSet result = select.executeQuery()) { return result.next(); }
        } catch (SQLException e) {
            throw new StorageException("Cannot inspect " + key.value(), e);
        }
    }

    @Override
    public void close() { pool.close(); }

    /** Evaluate time inside the write predicate, after any lock wait, rather than using a stale client timestamp. */
    private String clock() {
        return sqlite ? "CAST(strftime('%s', 'now') AS INTEGER) * 1000"
                : "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)";
    }

    private void identify(PreparedStatement statement, StorageKey key, int offset) throws SQLException {
        statement.setString(offset, namespace);
        statement.setString(offset + 1, key.value());
    }

    private static void validateLease(String owner, long leaseMillis) {
        if (owner == null || owner.isEmpty() || owner.length() > 64 || leaseMillis < 1000) {
            throw new IllegalArgumentException("Invalid inventory ownership lease");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }
}
