package ru.endlesscode.rpginventory.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercise queue ordering and recovery bytes using SQLite; backend failure must never acknowledge a save. */
public class StorageServiceTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void snapshotsAreImmutableAndFinalSaveDrainsBeforeShutdown() throws Exception {
        String url = "jdbc:sqlite:" + directory.newFile("ordered.db");
        var repository = new JdbcInventoryRepository(url, "", "", "test", 1);
        var recovery = directory.getRoot().toPath().resolve("recovery");
        var service = new StorageService(repository, 60000, recovery, (session, failure) -> fail(failure.toString()));
        var key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
        StorageSession session = service.acquire(key).get(5, TimeUnit.SECONDS);
        byte[] bytes = {1, 2, 3};
        var first = service.save(session, bytes, false);
        bytes[0] = 99;
        first.get(5, TimeUnit.SECONDS);
        // Reacquiring with the existing token reads the committed bytes without transferring ownership.
        assertArrayEquals(new byte[] {1, 2, 3}, repository.acquire(key, session.owner(), 60000).payload());
        var last = service.save(session, new byte[] {4, 5}, true);
        service.close();
        last.get(5, TimeUnit.SECONDS);
        try (var reopened = new JdbcInventoryRepository(url, "", "", "test", 1)) {
            StoredRecord saved = reopened.acquire(key, "new-server", 60000);
            assertEquals(2, saved.revision());
            assertArrayEquals(new byte[] {4, 5}, saved.payload());
        }
        try (var files = Files.list(recovery)) { assertEquals(0, files.count()); }
    }

    @Test
    public void reconnectWaitsForQueuedFinalSave() throws Exception {
        var repository = org.mockito.Mockito.mock(InventoryRepository.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
        org.mockito.Mockito.when(repository.acquire(org.mockito.ArgumentMatchers.eq(key),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(60000L)))
                .thenReturn(new StoredRecord(null, 0), new StoredRecord(new byte[] {2}, 1));
        // Block the actual backend call so reconnect is guaranteed to arrive before quit has committed.
        org.mockito.Mockito.when(repository.save(org.mockito.ArgumentMatchers.eq(key),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.eq(60000L),
                org.mockito.ArgumentMatchers.eq(true))).thenAnswer(invocation -> {
                    entered.countDown();
                    assertTrue(resume.await(5, TimeUnit.SECONDS));
                    return 1L;
                });
        try (var service = new StorageService(repository, 60000,
                directory.getRoot().toPath().resolve("recovery"), (session, failure) -> fail(failure.toString()))) {
            StorageSession old = service.acquire(key).get(5, TimeUnit.SECONDS);
            var last = service.save(old, new byte[] {2}, true);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var reconnected = service.acquire(key);
            resume.countDown();
            last.get(5, TimeUnit.SECONDS);
            StorageSession current = reconnected.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] {2}, current.initialPayload());
            assertFalse(old.isActive());
            service.release(current).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void lostOwnershipPreservesRecoveryAndDoesNotOverwriteNewerData() throws Exception {
        String url = "jdbc:sqlite:" + directory.newFile("failure.db");
        var repository = new JdbcInventoryRepository(url, "", "", "test", 1);
        var recovery = directory.getRoot().toPath().resolve("recovery");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var service = new StorageService(repository, 60000, recovery, (session, cause) -> failure.set(cause))) {
            var key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
            StorageSession session = service.acquire(key).get(5, TimeUnit.SECONDS);
            // Simulate a lease transfer; the stale worker must fence itself before acknowledging this write.
            repository.release(key, session.owner());
            repository.acquire(key, "other-server", 60000);
            repository.save(key, "other-server", 0, new byte[] {9}, 60000, false);
            assertThrows(ExecutionException.class, () -> service.save(session, new byte[] {1, 2, 3}, false).get(5, TimeUnit.SECONDS));
            assertFalse(session.isActive());
            assertNotNull(failure.get());
            try (var files = Files.list(recovery)) {
                var saved = files.toList();
                assertEquals(1, saved.size());
                assertTrue(Files.readString(saved.getFirst()).contains("payload=AQID"));
            }
            repository.release(key, "other-server");
            assertArrayEquals(new byte[] {9}, repository.acquire(key, "verify", 60000).payload());
        }
    }

    @Test
    public void serializationFailureFreezesOnceAndKeepsBackendUntouched() throws Exception {
        String url = "jdbc:sqlite:" + directory.newFile("serialization.db");
        var repository = new JdbcInventoryRepository(url, "", "", "test", 1);
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        try (var service = new StorageService(repository, 60000,
                directory.getRoot().toPath().resolve("recovery"), (session, cause) -> {
                    assertFalse(session.isActive());
                    callbacks.incrementAndGet();
                })) {
            var key = new StorageKey(StorageKey.Kind.PLAYER, UUID.randomUUID());
            StorageSession session = service.acquire(key).get(5, TimeUnit.SECONDS);
            var failure = new IllegalArgumentException("Cannot encode an item");
            service.serializationFailed(session, failure);
            // A quit callback may attempt serialization again; it must not recursively kick the same player.
            service.serializationFailed(session, failure);
            assertEquals(1, callbacks.get());
            assertFalse(session.isActive());
            assertFalse(repository.hasData(key));
            assertThrows(ExecutionException.class, () -> service.acquire(key).get(5, TimeUnit.SECONDS));
        }
    }
}
