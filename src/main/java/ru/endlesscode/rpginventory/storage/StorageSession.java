package ru.endlesscode.rpginventory.storage;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Fenced ownership of one inventory; a unique token is issued for every load, including reconnects. */
public final class StorageSession {
    private final StorageKey key;
    private final String owner = UUID.randomUUID().toString();
    private volatile boolean valid = true;
    private volatile long deadline;
    private volatile long revision;
    private byte[] initialPayload;

    StorageSession(StorageKey key) { this.key = key; }

    /** Stable identity used by the UI to freeze exactly the inventory whose ownership was lost. */
    public StorageKey key() { return key; }

    /** Check on every inventory interaction, including after a long server pause. */
    public boolean isActive() { return valid && System.nanoTime() < deadline; }

    /** Snapshot handed to the main thread for deserialization; backend I/O never touches Bukkit items. */
    public byte[] initialPayload() { return initialPayload == null ? null : initialPayload.clone(); }

    void loaded(StoredRecord record, long started, long leaseMillis) {
        revision = record.revision();
        initialPayload = record.payload();
        renewed(started, leaseMillis);
    }

    void renewed(long started, long leaseMillis) {
        // Measure from request start, not response arrival: network delay must not extend local ownership.
        deadline = started + TimeUnit.MILLISECONDS.toNanos(leaseMillis - 1000);
    }

    String owner() { return owner; }
    long revision() { return revision; }
    void committed(long value) { revision = value; }
    void invalidate() { valid = false; }
    boolean wasInvalidated() { return !valid; }
}
