package ru.endlesscode.rpginventory.storage;

import org.junit.Assume;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/** Opt-in tests run the same ownership contract against real PostgreSQL services, not in-memory substitutes. */
public class RemoteRepositoryTest {
    @Test public void postgresqlOwnershipContract() {
        String url = System.getenv("SX_RPG_TEST_POSTGRES_URL");
        Assume.assumeTrue("PostgreSQL integration environment not configured", url != null);
        try (var repository = new JdbcInventoryRepository(url, System.getenv("SX_RPG_TEST_POSTGRES_USER"),
                System.getenv("SX_RPG_TEST_POSTGRES_PASSWORD"), "test_" + UUID.randomUUID().toString().replace("-", ""), 2)) {
            verify(repository);
        }
    }

    private void verify(InventoryRepository repository) {
        StorageKey key = new StorageKey(StorageKey.Kind.BACKPACK, UUID.randomUUID());
        assertNull(repository.acquire(key, "a", 60000).payload());
        assertThrows(StorageConflictException.class, () -> repository.acquire(key, "b", 60000));
        assertEquals(1, repository.save(key, "a", 0, new byte[] {0, -1, 5}, 60000, true));
        StoredRecord record = repository.acquire(key, "b", 60000);
        assertArrayEquals(new byte[] {0, -1, 5}, record.payload());
        assertThrows(StorageConflictException.class, () -> repository.save(key, "a", 0, new byte[0], 60000, false));
        repository.release(key, "a");
        repository.renew(key, "b", 60000);
        repository.release(key, "b");
        assertTrue(repository.hasData(key));
    }
}
