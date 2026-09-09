package ru.endlesscode.rpginventory.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Ordered, bounded asynchronous storage. Callers serialize immutable snapshots on the server thread;
 * this executor does only backend and journal I/O. A rejected/failed write is never reported as saved.
 */
public final class StorageService implements AutoCloseable {
    private final InventoryRepository repository;
    private final long leaseMillis;
    private final Path recoveryDirectory;
    private final BiConsumer<StorageSession, Throwable> failureHandler;
    private final Map<StorageKey, StorageSession> sessions = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    // Rejected normal writes still need an off-thread recovery path; this queue never retries database writes.
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SX-RPGInventory-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closing;

    /** Failure callbacks may run on the caller or storage thread; adapters must dispatch Bukkit mutations safely. */
    public StorageService(InventoryRepository repository, long leaseMillis, Path recoveryDirectory,
                          BiConsumer<StorageSession, Throwable> failureHandler) {
        if (leaseMillis < 15000) throw new IllegalArgumentException("Ownership lease must be at least 15 seconds");
        this.repository = repository;
        this.leaseMillis = leaseMillis;
        this.recoveryDirectory = recoveryDirectory;
        this.failureHandler = failureHandler;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1024), task -> {
            Thread thread = new Thread(task, "SX-RPGInventory-storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Claim before constructing any mutable inventory; an unavailable record never yields default equipment. */
    public CompletableFuture<StorageSession> acquire(StorageKey key) {
        StorageSession session = new StorageSession(key);
        return submit(() -> {
            // Reserve in queue order: a reconnect/reload must observe the preceding final save's release.
            if (sessions.putIfAbsent(key, session) != null) throw new StorageConflictException(key);
            long started = System.nanoTime();
            try {
                session.loaded(repository.acquire(key, session.owner(), leaseMillis), started, leaseMillis);
                requireActive(session);
                return session;
            } catch (RuntimeException failure) {
                session.invalidate();
                sessions.remove(key, session);
                throw failure;
            }
        }).whenComplete((result, failure) -> {
            if (failure != null) sessions.remove(key, session);
        });
    }

    /** Save ordering also covers quit/reconnect; release is part of the final conditional backend write. */
    public CompletableFuture<Void> save(StorageSession session, byte[] snapshot, boolean release) {
        byte[] payload = snapshot.clone();
        AtomicBoolean journalWritten = new AtomicBoolean();
        CompletableFuture<Void> attempt = this.<Void>submit(() -> {
            Path recovery = null;
            try {
                // Journal before checking the lease, so an expired session still leaves recoverable bytes.
                recovery = journal(session, payload);
                journalWritten.set(true);
                requireActive(session);
                long started = System.nanoTime();
                session.committed(repository.save(session.key(), session.owner(), session.revision(), payload, leaseMillis, release));
                if (release) {
                    session.invalidate();
                    sessions.remove(session.key(), session);
                } else {
                    session.renewed(started, leaseMillis);
                }
            } catch (RuntimeException | IOException failure) {
                session.invalidate();
                throw new StorageException("Inventory save failed: " + session.key().value(), failure);
            }
            try { Files.deleteIfExists(recovery); }
            catch (IOException failure) {
                // The database committed already; cleanup failure must not cause a second write or invalidate it.
                java.util.logging.Logger.getLogger("SX-RPGInventory").log(java.util.logging.Level.WARNING,
                        "Committed recovery journal needs cleanup: " + recovery, failure);
            }
            return null;
        });
        // handle + thenCompose retains the asynchronous recovery barrier on Java 8 as well.
        return attempt.handle((ignored, failure) -> {
            if (failure == null) return CompletableFuture.<Void>completedFuture(null);
            session.invalidate();
            try { failureHandler.accept(session, failure); }
            catch (RuntimeException callbackFailure) { failure.addSuppressed(callbackFailure); }
            if (journalWritten.get()) return StorageService.<Void>failedFuture(failure);
            CompletableFuture<Void> recovered = new CompletableFuture<>();
            try {
                recoveryExecutor.execute(() -> {
                    try { journal(session, payload); }
                    catch (IOException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
                    recovered.completeExceptionally(failure);
                });
            } catch (RejectedExecutionException rejected) {
                failure.addSuppressed(rejected);
                recovered.completeExceptionally(failure);
            }
            return recovered;
        }).thenCompose(result -> result);
    }

    /** Abandon a cancelled load; never use this in place of saving an inventory that has been edited. */
    public CompletableFuture<Void> release(StorageSession session) {
        session.invalidate();
        return submit(() -> {
            repository.release(session.key(), session.owner());
            sessions.remove(session.key(), session);
            return null;
        });
    }

    /** Freeze a live inventory when main-thread serialization fails, before an error callback can trigger quit. */
    public void serializationFailed(StorageSession session, Throwable failure) {
        boolean firstFailure = !session.wasInvalidated();
        session.invalidate();
        // A quit/save re-entry must not schedule repeated kicks for the same failing inventory.
        if (firstFailure) failureHandler.accept(session, failure);
    }

    /** Renew all current leases in queue order; adapters call at less than one third of the configured lease. */
    public CompletableFuture<Void> heartbeat() {
        return submit(() -> {
            for (StorageSession session : sessions.values()) {
                if (session.wasInvalidated()) continue;
                if (!session.isActive()) {
                    session.invalidate();
                    failureHandler.accept(session, new StorageConflictException(session.key()));
                    continue;
                }
                try {
                    long started = System.nanoTime();
                    repository.renew(session.key(), session.owner(), leaseMillis);
                    session.renewed(started, leaseMillis);
                } catch (RuntimeException failure) {
                    session.invalidate();
                    failureHandler.accept(session, failure);
                }
            }
            return null;
        });
    }

    /** Existing call sites can make first-use decisions without reading the filesystem on the server thread. */
    public CompletableFuture<Boolean> hasData(StorageKey key) {
        return submit(() -> repository.hasData(key));
    }

    /** Read legacy files off-thread only while holding the new record's lease; original files remain backups. */
    public CompletableFuture<byte[]> readLegacy(StorageSession session, Path file) {
        return submit(() -> {
            requireActive(session);
            try { return Files.exists(file) ? Files.readAllBytes(file) : null; }
            catch (IOException e) { throw new StorageException("Cannot read legacy inventory file", e); }
        });
    }

    /** Stop accepting work, drain bounded outstanding I/O, then release the pool only after the worker exits. */
    @Override
    public void close() {
        closing = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                // Never close a connection pool underneath a still-running commit.
                throw new StorageException("Storage shutdown timed out; recovery journal must be inspected");
            }
            recoveryExecutor.shutdown();
            if (!recoveryExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                throw new StorageException("Recovery journal shutdown timed out");
            }
            repository.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while draining inventory storage", e);
        }
    }

    private void requireActive(StorageSession session) {
        if (sessions.get(session.key()) != session || !session.isActive()) {
            throw new StorageConflictException(session.key());
        }
    }

    private <T> CompletableFuture<T> submit(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closing) return failedFuture(new StorageException("Inventory storage is closing"));
        try {
            executor.execute(() -> {
                try { result.complete(action.get()); }
                catch (Throwable failure) {
                    result.completeExceptionally(failure);
                    if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
                    if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(new StorageException("Inventory storage queue is full", failure));
        }
        return result;
    }

    /** Java 8 equivalent of failedFuture; callers still observe the original exceptional completion. */
    private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    /**
     * Local recovery records carry the expected fencing revision and owner. They are NOT replayed blindly:
     * an operator must compare against the authoritative backend to avoid overwriting a newer server's save.
     */
    private Path journal(StorageSession session, byte[] payload) throws IOException {
        Files.createDirectories(recoveryDirectory);
        // Multiple failed snapshots may share a revision; unique names preserve each recovery candidate.
        String name = session.key().value().replace(':', '-') + "-" + session.owner() + "-" + session.revision()
                + "-" + java.util.UUID.randomUUID() + ".recovery";
        Path target = recoveryDirectory.resolve(name);
        Path temp = Files.createTempFile(recoveryDirectory, name, ".tmp");
        byte[] encoded = ("format=1\nkey=" + session.key().value() + "\nowner=" + session.owner()
                + "\nrevision=" + session.revision() + "\npayload=" + Base64.getEncoder().encodeToString(payload) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
