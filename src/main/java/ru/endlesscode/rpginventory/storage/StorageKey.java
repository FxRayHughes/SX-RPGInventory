package ru.endlesscode.rpginventory.storage;

import java.util.Objects;
import java.util.UUID;

/** Stable record identity shared by every backend; never persist enum ordinals or player names. */
public final class StorageKey {
    private final Kind kind;
    private final UUID id;

    /** Keep identity immutable so equal keys select the same lease even on Java 8 servers. */
    public StorageKey(Kind kind, UUID id) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.id = Objects.requireNonNull(id, "id");
    }

    /** Player and backpack UUIDs occupy separate persistent identity domains. */
    public Kind kind() { return kind; }

    /** Return the stable UUID rather than a mutable player name or carrier identity. */
    public UUID id() { return id; }

    /** Value equality is required by the session map to fence separately constructed keys. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StorageKey)) return false;
        StorageKey key = (StorageKey) other;
        return kind == key.kind && id.equals(key.id);
    }

    @Override
    public int hashCode() { return 31 * kind.hashCode() + id.hashCode(); }

    @Override
    public String toString() { return "StorageKey[kind=" + kind + ", id=" + id + "]"; }

    /** Persisted key prefixes are a protocol: renaming one requires an explicit migration. */
    public enum Kind {
        PLAYER("player"), BACKPACK("backpack");
        private final String prefix;
        Kind(String prefix) { this.prefix = prefix; }
    }

    /** UUID-based identities keep a backpack independent of whoever is currently carrying it. */
    public String value() {
        return kind.prefix + ":" + id;
    }
}
