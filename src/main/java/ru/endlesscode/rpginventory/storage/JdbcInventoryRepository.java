package ru.endlesscode.rpginventory.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * SQLite/PostgreSQL/MySQL repository with pooled connections, database-clock leases and conditional writes.
 * The namespace partitions independent networks sharing one database without interpolating table names.
 */
public final class JdbcInventoryRepository implements InventoryRepository {
    private final HikariDataSource pool;
    private final InventoryPayloadCache cache;
    private final String namespace;
    private final boolean sqlite;
    private final boolean mysql;
    // Stable schema name and column meanings; migrations must preserve payload, revision and ownership together.
    private static final String TABLE = "sx_rpginventory_records";

    /** Create schema once; SQLite uses one WAL writer with a busy timeout rather than competing writers. */
    public JdbcInventoryRepository(String url, String username, String password, String namespace, int poolSize) {
        this(url, username, password, namespace, poolSize, null);
    }

    /** 可选缓存只加速快照读取，SQL 始终负责租约、版本及持久化；旧构造器保持默认无缓存。 */
    public JdbcInventoryRepository(String url, String username, String password, String namespace, int poolSize,
                                   InventoryPayloadCache cache) {
        this.cache = cache;
        this.sqlite = url.startsWith("jdbc:sqlite:");
        this.mysql = url.startsWith("jdbc:mysql:");
        if (!sqlite && !mysql && !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Only SQLite, PostgreSQL and MySQL JDBC URLs are supported");
        }
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        HikariConfig config = new HikariConfig();
        config.setPoolName("SX-RPGInventory");
        config.setJdbcUrl(url);
        config.setDriverClassName(sqlite ? "org.sqlite.JDBC" : mysql ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
        config.setMaximumPoolSize(sqlite ? 1 : Math.max(1, poolSize));
        config.setConnectionTimeout(10000);
        if (sqlite) {
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "FULL");
            config.addDataSourceProperty("busy_timeout", "10000");
        } else {
            config.setUsername(username);
            config.setPassword(password);
            // Connector/J measures these limits in milliseconds; PostgreSQL's driver uses seconds.
            config.addDataSourceProperty("connectTimeout", mysql ? "10000" : "10");
            config.addDataSourceProperty("socketTimeout", mysql ? "15000" : "15");
        }
        pool = new HikariDataSource(config);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            // MySQL's default text collation is often case-insensitive. Binary identities also preserve trailing bytes.
            // InnoDB row locks and LONGBLOB are part of this backend's protocol, not configurable schema defaults.
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "namespace " + (mysql ? "VARBINARY(128)" : "VARCHAR(128)") + " NOT NULL, "
                    + "record_key " + (mysql ? "VARBINARY(64)" : "VARCHAR(64)") + " NOT NULL, "
                    + "payload " + (sqlite ? "BLOB" : mysql ? "LONGBLOB" : "BYTEA") + ", revision BIGINT NOT NULL DEFAULT 0, "
                    + "owner " + (mysql ? "VARBINARY(256)" : "VARCHAR(64)")
                    + ", lease_until BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (namespace, record_key))" + (mysql ? " ENGINE=InnoDB" : ""));
            if (mysql) validateMysqlSchema(connection);
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
                        : mysql ? "INSERT INTO " + TABLE + " (namespace, record_key) VALUES (?, ?) ON DUPLICATE KEY UPDATE record_key=record_key"
                        : "INSERT INTO " + TABLE + " (namespace, record_key) VALUES (?, ?) ON CONFLICT (namespace, record_key) DO NOTHING";
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    identify(insert, key, 1);
                    insert.executeUpdate();
                }
                String now = writeClock(connection, key);
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                        + " SET owner=?, lease_until=" + now + "+? WHERE namespace=? AND record_key=? AND (lease_until<=" + now + " OR owner=?)")) {
                    setOwner(update, 1, owner);
                    update.setLong(2, leaseMillis);
                    identify(update, key, 3);
                    setOwner(update, 5, owner);
                    if (update.executeUpdate() != 1) throw new StorageConflictException(key);
                }
                StoredRecord record = readClaimedRecord(connection, key);
                connection.commit();
                // 只有 SQL 成功提交的版本才能进入缓存；失败的缓存发布不改变已取得的租约结果。
                if (cache != null) cachePut(key, record.revision(), record.payload());
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
        long savedRevision;
        try (Connection connection = pool.getConnection()) {
            savedRevision = conditionalWrite(connection, key, now -> {
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                        + " SET payload=?, revision=revision+1, owner=?, lease_until=CASE WHEN ?=1 THEN 0 ELSE " + now + "+? END, updated_at=" + now
                        + " WHERE namespace=? AND record_key=? AND owner=? AND revision=? AND lease_until>" + now)) {
                    update.setBytes(1, payload);
                    setOwner(update, 2, release ? null : owner);
                    update.setInt(3, release ? 1 : 0);
                    update.setLong(4, leaseMillis);
                    identify(update, key, 5);
                    setOwner(update, 7, owner);
                    update.setLong(8, revision);
                    if (update.executeUpdate() != 1) throw new StorageConflictException(key);
                    return revision + 1;
                }
            });
        } catch (SQLException e) {
            throw new StorageException("Cannot save " + key.value(), e);
        }
        // SQL 提交成功以后才发布新版本；迟到的旧版本缓存写入也只能写自己的版本键。
        cachePut(key, savedRevision, payload);
        return savedRevision;
    }

    @Override
    public void renew(StorageKey key, String owner, long leaseMillis) {
        validateLease(owner, leaseMillis);
        try (Connection connection = pool.getConnection()) {
            conditionalWrite(connection, key, now -> {
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                        + " SET lease_until=" + now + "+? WHERE namespace=? AND record_key=? AND owner=? AND lease_until>" + now)) {
                    update.setLong(1, leaseMillis);
                    identify(update, key, 2);
                    setOwner(update, 4, owner);
                    if (update.executeUpdate() != 1) throw new StorageConflictException(key);
                    return null;
                }
            });
        } catch (SQLException e) {
            throw new StorageException("Cannot renew " + key.value(), e);
        }
    }

    @Override
    public void release(StorageKey key, String owner) {
        try (Connection connection = pool.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE + " SET owner=NULL, lease_until=0 WHERE namespace=? AND record_key=? AND owner=?")) {
            identify(update, key, 1);
            setOwner(update, 3, owner);
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
    public void close() {
        try { pool.close(); }
        finally {
            if (cache != null) {
                try { cache.close(); } catch (RuntimeException ignored) { /* 缓存关闭不改变 SQL 已提交的数据。 */ }
            }
        }
    }

    /** 持有 SQL 租约后读取版本；关闭缓存时仍只执行原有的一次完整快照查询。 */
    private StoredRecord readClaimedRecord(Connection connection, StorageKey key) throws SQLException {
        if (cache == null) {
            try (PreparedStatement select = connection.prepareStatement("SELECT payload, revision FROM " + TABLE
                    + " WHERE namespace=? AND record_key=?")) {
                identify(select, key, 1);
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) throw new StorageException("Claimed record disappeared: " + key.value());
                    return new StoredRecord(result.getBytes(1), result.getLong(2));
                }
            }
        }
        long revision;
        boolean hasPayload;
        // 这里只传输版本和存在标记；命中缓存不会从 SQL 取回大块物品数据。
        try (PreparedStatement select = connection.prepareStatement("SELECT revision, payload IS NOT NULL FROM " + TABLE
                + " WHERE namespace=? AND record_key=?")) {
            identify(select, key, 1);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) throw new StorageException("Claimed record disappeared: " + key.value());
                revision = result.getLong(1);
                hasPayload = result.getBoolean(2);
            }
        }
        if (!hasPayload) return new StoredRecord(null, revision);
        byte[] payload = null;
        try { payload = cache.get(key, revision); }
        catch (RuntimeException ignored) { /* 可选缓存故障必须回源 SQL，不能伪装成空背包。 */ }
        if (payload == null) {
            // 同一个事务持有记录锁，版本读取与回源期间不存在另一所有者插入新快照的窗口。
            try (PreparedStatement select = connection.prepareStatement("SELECT payload FROM " + TABLE
                    + " WHERE namespace=? AND record_key=?")) {
                identify(select, key, 1);
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) throw new StorageException("Claimed record disappeared: " + key.value());
                    payload = result.getBytes(1);
                    if (payload == null) throw new StorageException("Claimed payload disappeared: " + key.value());
                }
            }
        }
        return new StoredRecord(payload, revision);
    }

    /** 缓存不是提交事务的一部分，任何缓存运行异常都不能把成功持久化误报为失败。 */
    private void cachePut(StorageKey key, long revision, byte[] payload) {
        if (cache != null && payload != null) {
            try { cache.put(key, revision, payload); }
            catch (RuntimeException ignored) { /* 数据已在 SQL 中，后续读取会按同一版本重新填充缓存。 */ }
        }
    }

    /** SQLite/PG evaluate their existing write clocks; MySQL reads a separate statement only after the row lock. */
    private String clock() {
        return sqlite ? "CAST(strftime('%s', 'now') AS INTEGER) * 1000"
                : "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)";
    }

    private void identify(PreparedStatement statement, StorageKey key, int offset) throws SQLException {
        if (mysql) {
            statement.setBytes(offset, namespace.getBytes(StandardCharsets.UTF_8));
            statement.setBytes(offset + 1, key.value().getBytes(StandardCharsets.UTF_8));
        } else {
            statement.setString(offset, namespace);
            statement.setString(offset + 1, key.value());
        }
    }

    private void setOwner(PreparedStatement statement, int index, String owner) throws SQLException {
        if (mysql) statement.setBytes(index, owner == null ? null : owner.getBytes(StandardCharsets.UTF_8));
        else statement.setString(index, owner);
    }

    /** NOW is fixed at statement start in MySQL, even before an InnoDB wait. Lock first, then read database time. */
    private String writeClock(Connection connection, StorageKey key) throws SQLException {
        if (!mysql) return clock();
        try (PreparedStatement lock = connection.prepareStatement("SELECT record_key FROM " + TABLE
                + " WHERE namespace=? AND record_key=? FOR UPDATE")) {
            identify(lock, key, 1);
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next()) throw new StorageConflictException(key);
            }
        }
        // Separate statement starts after the lock is held; sysdate-is-now and server clock offsets cannot weaken fencing.
        try (Statement clock = connection.createStatement(); ResultSet result = clock.executeQuery(
                "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)")) {
            if (!result.next()) throw new SQLException("Database clock returned no value");
            return Long.toString(result.getLong(1));
        }
    }

    /** Keep MySQL's lock, clock and CAS in one transaction; SQLite/PG retain their original single-statement writes. */
    private <T> T conditionalWrite(Connection connection, StorageKey key, TimedWrite<T> write) throws SQLException {
        if (mysql) connection.setAutoCommit(false);
        try {
            T result = write.execute(writeClock(connection, key));
            if (mysql) connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            if (mysql) rollback(connection, failure);
            throw failure;
        }
    }

    /** Callback receives only a database-generated millisecond integer or a fixed trusted SQL clock expression. */
    private interface TimedWrite<T> { T execute(String now) throws SQLException; }

    /** Reject pre-existing incompatible schemas rather than silently running without exact identities or row locks. */
    private static void validateMysqlSchema(Connection connection) throws SQLException {
        try (PreparedStatement table = connection.prepareStatement("SELECT ENGINE FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            table.setString(1, TABLE);
            try (ResultSet result = table.executeQuery()) {
                if (!result.next() || !"InnoDB".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("MySQL inventory table must use InnoDB");
                }
            }
        }
        try (PreparedStatement columns = connection.prepareStatement("SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME IN ('namespace','record_key','owner','payload')")) {
            columns.setString(1, TABLE);
            int count = 0;
            try (ResultSet result = columns.executeQuery()) {
                while (result.next()) {
                    String expected = "payload".equals(result.getString(1)) ? "longblob" : "varbinary";
                    if (!expected.equalsIgnoreCase(result.getString(2))) throw new SQLException("Incompatible MySQL inventory column: " + result.getString(1));
                    count++;
                }
            }
            if (count != 4) throw new SQLException("Incomplete MySQL inventory schema");
        }
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
