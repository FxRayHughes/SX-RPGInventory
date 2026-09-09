package ru.endlesscode.rpginventory.storage;

/**
 * Blocking persistence boundary, invoked only by the storage executor. A lease gates access to the
 * live inventory; the revision also fences delayed writes from an older session after a server crash.
 * Implementations preserve records on failure and never expire the payload with the ownership lease.
 */
public interface InventoryRepository extends AutoCloseable {
    /** Atomically claim ownership and read the last committed payload; a busy record throws a conflict. */
    StoredRecord acquire(StorageKey key, String owner, long leaseMillis);

    /** Commit only with a live lease and the expected revision; optionally release in the same transaction. */
    long save(StorageKey key, String owner, long revision, byte[] payload, long leaseMillis, boolean release);

    /** Renew live ownership only; an expired session cannot revive itself after losing the record. */
    void renew(StorageKey key, String owner, long leaseMillis);

    /** Release an abandoned load without modifying inventory data or another session's lease. */
    void release(StorageKey key, String owner);

    /** Distinguish first use from a reserved but still empty record. */
    boolean hasData(StorageKey key);

    /** Called after pending saves have drained, never while an operation is using the pool. */
    @Override
    void close();
}
