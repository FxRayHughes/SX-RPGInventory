package ru.endlesscode.rpginventory.storage;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real MySQL 8 InnoDB tests; an observed database lock wait, not a sleep alone, establishes the expiry race. */
public class MysqlInventoryRepositoryTest {
    private static final String TABLE = "sx_rpginventory_records";
    private static final String CLOCK = "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)";
    private String url;
    private String user;
    private String password;
    private String namespace;

    @Before public void requireMysqlEnvironment() {
        url = System.getenv("SX_RPG_TEST_MYSQL_URL");
        Assume.assumeTrue("MySQL integration environment not configured", url != null);
        user = System.getenv("SX_RPG_TEST_MYSQL_USER");
        password = System.getenv("SX_RPG_TEST_MYSQL_PASSWORD");
        namespace = "MySQL_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test public void factoryStoresLargeBinaryPayloadAndPreservesFencingAcrossPoolRestart() throws Exception {
        StorageKey key = new StorageKey(StorageKey.Kind.BACKPACK, UUID.randomUUID());
        byte[] payload = new byte[1024 * 1024 + 17];
        new Random(71).nextBytes(payload);
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("storage.backend", "MYSQL");
        config.set("storage.namespace", namespace);
        config.set("storage.mysql.url", url);
        config.set("storage.mysql.username", user);
        config.set("storage.mysql.password", password);
        config.set("storage.mysql.pool-size", 2);
        try (InventoryRepository repository = RepositoryFactory.open(config, Paths.get("unused-mysql-directory"))) {
            assertNull(repository.acquire(key, "Owner", 60000).payload());
            assertFalse(repository.hasData(key));
            assertThrows(StorageConflictException.class, () -> repository.acquire(key, "Other", 60000));
            assertEquals(1, repository.save(key, "Owner", 0, payload, 60000, true));
        }
        try (InventoryRepository repository = repository(namespace)) {
            StoredRecord restored = repository.acquire(key, "NewOwner", 60000);
            assertArrayEquals(payload, restored.payload());
            assertEquals(1, restored.revision());
            assertTrue(repository.hasData(key));
            repository.release(key, "Owner");
            assertThrows(StorageConflictException.class, () -> repository.save(key, "Owner", 0, new byte[0], 60000, true));
            repository.renew(key, "NewOwner", 60000);
            repository.release(key, "NewOwner");
        }
    }

    @Test public void namespaceAndOwnerComparisonsAreByteExact() throws Exception {
        UUID id = UUID.randomUUID();
        StorageKey key = new StorageKey(StorageKey.Kind.PLAYER, id);
        StorageKey backpack = new StorageKey(StorageKey.Kind.BACKPACK, id);
        try (InventoryRepository upper = repository(namespace);
             InventoryRepository lower = repository(namespace.toLowerCase(java.util.Locale.ROOT))) {
            upper.acquire(key, "Owner", 60000);
            assertNull(lower.acquire(key, "Owner", 60000).payload());
            assertNull(upper.acquire(backpack, "Owner", 60000).payload());
            assertThrows(StorageConflictException.class, () -> upper.acquire(key, "owner", 60000));
            assertThrows(StorageConflictException.class, () -> upper.renew(key, "Owner ", 60000));
            assertThrows(StorageConflictException.class, () -> upper.save(key, "owner", 0, new byte[] {1}, 60000, true));
            upper.release(key, "owner");
            upper.release(key, "Owner ");
            upper.renew(key, "Owner", 60000);
            assertEquals(1, upper.save(key, "Owner", 0, new byte[] {2}, 60000, true));
            assertFalse(lower.hasData(key));
            assertFalse(upper.hasData(backpack));
        }
    }

    @Test public void concurrentSameRevisionWritesHaveExactlyOneWinner() throws Exception {
        StorageKey key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (InventoryRepository repository = repository(namespace)) {
            repository.acquire(key, "Owner", 60000);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = workers.submit(() -> saveWhenReleased(repository, key, start, (byte) 1));
            Future<Boolean> second = workers.submit(() -> saveWhenReleased(repository, key, start, (byte) 2));
            start.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
            StoredRecord result = repository.acquire(key, "Owner", 60000);
            assertEquals(1, result.revision());
            assertEquals(1, result.payload().length);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test public void saveRejectsLeaseThatExpiresWhileWaitingForRowLock() throws Exception {
        verifyExpiryAfterObservedLockWait(WaitOperation.SAVE);
    }

    @Test public void renewRejectsLeaseThatExpiresWhileWaitingForRowLock() throws Exception {
        verifyExpiryAfterObservedLockWait(WaitOperation.RENEW);
    }

    @Test public void acquireCanClaimLeaseThatExpiresWhileWaitingForRowLock() throws Exception {
        verifyExpiryAfterObservedLockWait(WaitOperation.ACQUIRE);
    }

    private enum WaitOperation { SAVE, RENEW, ACQUIRE }

    /** A second connection holds the record while performance_schema proves the repository is blocked on that lock. */
    private void verifyExpiryAfterObservedLockWait(WaitOperation operation) throws Exception {
        StorageKey key = new StorageKey(StorageKey.Kind.BACKPACK, UUID.randomUUID());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (InventoryRepository repository = repository(namespace);
             Connection blocker = DriverManager.getConnection(url, user, password)) {
            repository.acquire(key, "Owner", 60000);
            blocker.setAutoCommit(false);
            long connectionId;
            long expiry;
            try (Statement statement = blocker.createStatement();
                 ResultSet row = statement.executeQuery("SELECT CONNECTION_ID(), " + CLOCK + " + 1200")) {
                assertTrue(row.next());
                connectionId = row.getLong(1);
                expiry = row.getLong(2);
            }
            try (PreparedStatement update = blocker.prepareStatement("UPDATE " + TABLE
                    + " SET lease_until=? WHERE namespace=? AND record_key=?")) {
                update.setLong(1, expiry);
                identify(update, key, 2);
                assertEquals(1, update.executeUpdate());
            }
            Future<?> waiting = worker.submit(() -> {
                switch (operation) {
                    case SAVE: repository.save(key, "Owner", 0, new byte[] {9}, 60000, true); break;
                    case RENEW: repository.renew(key, "Owner", 60000); break;
                    case ACQUIRE: repository.acquire(key, "NextOwner", 60000); break;
                    default: throw new AssertionError(operation);
                }
            });
            try {
                awaitRowLockWait(blocker, connectionId);
                assertFalse("operation must still be waiting on our record lock", waiting.isDone());
                // Establish expiry after the waiter has started: even a very slow test host cannot miss the race.
                try (Statement statement = blocker.createStatement(); ResultSet row = statement.executeQuery("SELECT " + CLOCK + " + 400")) {
                    assertTrue(row.next());
                    expiry = row.getLong(1);
                }
                try (PreparedStatement update = blocker.prepareStatement("UPDATE " + TABLE
                        + " SET lease_until=? WHERE namespace=? AND record_key=?")) {
                    update.setLong(1, expiry);
                    identify(update, key, 2);
                    assertEquals(1, update.executeUpdate());
                }
                awaitDatabaseTime(blocker, expiry + 50);
                blocker.commit();
                if (operation == WaitOperation.ACQUIRE) {
                    waiting.get(10, TimeUnit.SECONDS);
                    repository.renew(key, "NextOwner", 60000);
                    assertThrows(StorageConflictException.class, () -> repository.renew(key, "Owner", 60000));
                } else {
                    ExecutionException failure = assertThrows(ExecutionException.class,
                            () -> waiting.get(10, TimeUnit.SECONDS));
                    assertTrue("expired writer must be fenced: " + failure.getCause(),
                            failure.getCause() instanceof StorageConflictException);
                    assertNull(repository.acquire(key, "NextOwner", 60000).payload());
                }
                assertEquals(1, repository.save(key, "NextOwner", 0, new byte[] {1}, 60000, true));
            } finally {
                // Assertions must release the lock before pool shutdown waits for the blocked worker.
                blocker.rollback();
            }
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void awaitRowLockWait(Connection blocker, long connectionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try (PreparedStatement observation = blocker.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_lock_waits waits"
                + " JOIN performance_schema.threads threads ON threads.THREAD_ID=waits.BLOCKING_THREAD_ID"
                + " WHERE threads.PROCESSLIST_ID=?")) {
            observation.setLong(1, connectionId);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = observation.executeQuery()) {
                    assertTrue(result.next());
                    if (result.getInt(1) > 0) return;
                }
                Thread.sleep(10);
            }
        }
        fail("repository did not enter an observed InnoDB row-lock wait");
    }

    private void awaitDatabaseTime(Connection connection, long target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT " + CLOCK)) {
                assertTrue(result.next());
                if (result.getLong(1) >= target) return;
            }
            Thread.sleep(10);
        }
        fail("database clock did not advance beyond the held lease");
    }

    private static boolean saveWhenReleased(InventoryRepository repository, StorageKey key, CountDownLatch start, byte value)
            throws InterruptedException {
        start.await();
        try { repository.save(key, "Owner", 0, new byte[] {value}, 60000, false); return true; }
        catch (StorageConflictException expected) { return false; }
    }

    private JdbcInventoryRepository repository(String name) { return new JdbcInventoryRepository(url, user, password, name, 2); }

    private void identify(PreparedStatement statement, StorageKey key, int offset) throws Exception {
        statement.setBytes(offset, namespace.getBytes(StandardCharsets.UTF_8));
        statement.setBytes(offset + 1, key.value().getBytes(StandardCharsets.UTF_8));
    }
}
