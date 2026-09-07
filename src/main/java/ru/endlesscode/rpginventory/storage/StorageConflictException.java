package ru.endlesscode.rpginventory.storage;

/** Another session owns the record, or the caller's fencing revision/lease is no longer valid. */
public final class StorageConflictException extends StorageException {
    public StorageConflictException(StorageKey key) {
        super("Inventory ownership or revision conflict: " + key.value());
    }
}
