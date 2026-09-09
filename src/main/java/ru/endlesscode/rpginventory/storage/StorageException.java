package ru.endlesscode.rpginventory.storage;

/** Backend failures must propagate to the session layer; they must never become an empty inventory. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) { super(message, cause); }
    public StorageException(String message) { super(message); }
}
