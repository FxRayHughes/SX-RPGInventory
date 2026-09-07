package ru.endlesscode.rpginventory.storage;

/** Immutable I/O snapshot; null payload means no inventory has ever been saved, not a load failure. */
public final class StoredRecord {
    private final byte[] payload;
    private final long revision;

    /** Snapshot ownership stays inside this value object while callers retain their original byte buffers. */
    public StoredRecord(byte[] payload, long revision) {
        this.payload = payload == null ? null : payload.clone();
        if (revision < 0) throw new IllegalArgumentException("Negative storage revision");
        this.revision = revision;
    }

    /** A defensive copy prevents queued asynchronous writes from observing subsequent mutation. */
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }

    /** The fencing revision must travel with the exact snapshot read from the backend. */
    public long revision() { return revision; }

    /** Preserve the former record's array-identity equality; byte content is not a storage identity. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StoredRecord)) return false;
        StoredRecord record = (StoredRecord) other;
        return payload == record.payload && revision == record.revision;
    }

    @Override
    public int hashCode() { return 31 * java.util.Objects.hashCode(payload) + Long.hashCode(revision); }

    @Override
    public String toString() { return "StoredRecord[payload=" + payload + ", revision=" + revision + "]"; }
}
