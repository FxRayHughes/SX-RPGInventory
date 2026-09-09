package ru.endlesscode.rpginventory.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.Assert.*;

/** Exercise real SQLite transactions, fencing, namespace isolation and persistence across pool restarts. */
public class JdbcInventoryRepositoryTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void persistsBinaryItemsAndFencesStaleWritersAcrossSessions() throws Exception {
        String url = "jdbc:sqlite:" + directory.newFile("inventories.db").getAbsolutePath();
        StorageKey key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
        byte[] payload = new byte[] {0, 1, -1, 0, 42};
        try (var first = new JdbcInventoryRepository(url, "", "", "test", 1);
             var second = new JdbcInventoryRepository(url, "", "", "test", 1)) {
            StoredRecord empty = first.acquire(key, "server-a", 60000);
            assertNull(empty.payload());
            assertFalse(first.hasData(key));
            assertThrows(StorageConflictException.class, () -> second.acquire(key, "server-b", 60000));
            assertEquals(1, first.save(key, "server-a", empty.revision(), payload, 60000, true));
            StoredRecord saved = second.acquire(key, "server-b", 60000);
            assertArrayEquals(payload, saved.payload());
            assertEquals(1, saved.revision());
            assertThrows(StorageConflictException.class, () -> first.save(key, "server-a", 0, new byte[0], 60000, false));
            // A stale release must not unlock a new owner's inventory.
            first.release(key, "server-a");
            assertThrows(StorageConflictException.class, () -> first.acquire(key, "server-c", 60000));
            second.release(key, "server-b");
        }
        try (var reopened = new JdbcInventoryRepository(url, "", "", "test", 1)) {
            assertArrayEquals(payload, reopened.acquire(key, "server-d", 60000).payload());
            assertTrue(reopened.hasData(key));
        }
    }

    @Test
    public void isolatesPlayerBackpackAndNetworkRecords() throws Exception {
        String url = "jdbc:sqlite:" + directory.newFile("namespaces.db").getAbsolutePath();
        UUID id = UUID.randomUUID();
        StorageKey player = new StorageKey(StorageKey.Kind.PLAYER, id);
        StorageKey backpack = new StorageKey(StorageKey.Kind.BACKPACK, id);
        try (var networkA = new JdbcInventoryRepository(url, "", "", "network-a", 1);
             var networkB = new JdbcInventoryRepository(url, "", "", "network-b", 1)) {
            networkA.acquire(player, "a", 60000);
            networkA.save(player, "a", 0, "items".getBytes(StandardCharsets.UTF_8), 60000, true);
            assertNull(networkA.acquire(backpack, "a", 60000).payload());
            assertNull(networkB.acquire(player, "b", 60000).payload());
        }
    }

    @Test
    public void expiredOwnershipCannotRenewOrOverwriteNewOwner() throws Exception {
        String url = "jdbc:sqlite:" + directory.newFile("expiry.db").getAbsolutePath();
        StorageKey key = new StorageKey(StorageKey.Kind.BACKPACK, UUID.randomUUID());
        try (var repository = new JdbcInventoryRepository(url, "", "", "test", 1)) {
            repository.acquire(key, "expired", 60000);
            // Advance authoritative lease state deterministically without wall-clock sleeps.
            try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE sx_rpginventory_records SET lease_until=0");
            }
            assertThrows(StorageConflictException.class, () -> repository.renew(key, "expired", 60000));
            assertThrows(StorageConflictException.class, () -> repository.save(key, "expired", 0, new byte[] {9}, 60000, true));
            repository.acquire(key, "new", 60000);
            assertEquals(1, repository.save(key, "new", 0, new byte[] {1}, 60000, true));
            assertArrayEquals(new byte[] {1}, repository.acquire(key, "verify", 60000).payload());
        }
    }
}
