package ru.endlesscode.rpginventory.compat.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable main-thread decisions consumed by network listeners; the payload type belongs to the chosen adapter. */
public final class CraftPacketState<T> {
    private final boolean recipeBlocked;
    private final boolean workbench;
    private final long capturedAt;
    private final Map<Integer, T> caps;

    /** The caller owns item conversion; only configured craft-grid slots may cross the publication boundary. */
    public CraftPacketState(boolean recipeBlocked, boolean workbench, long capturedAt, Map<Integer, T> caps) {
        this.recipeBlocked = recipeBlocked;
        this.workbench = workbench;
        this.capturedAt = capturedAt;
        Map<Integer, T> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, T> entry : caps.entrySet()) {
            if (entry.getKey() < 1 || entry.getKey() > 9) {
                throw new IllegalArgumentException("Craft overlay slot must be within raw grid slots 1..9");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        this.caps = Collections.unmodifiableMap(copy);
    }

    /** A paused server cannot keep authorizing autofill after inventory leases or permissions have expired. */
    public boolean blocksRecipe(long now) {
        return recipeBlocked || now - capturedAt > 1000000000L;
    }

    /** Positive matching container IDs exclude cursor (-1), player inventory (0/-2), and previous windows. */
    public Map<Integer, T> capsFor(int currentWindow, int packetWindow) {
        return workbench && currentWindow > 0 && currentWindow == packetWindow ? caps : Collections.emptyMap();
    }
}
