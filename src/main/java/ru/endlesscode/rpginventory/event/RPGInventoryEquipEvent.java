package ru.endlesscode.rpginventory.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Notification after committed RPG equipment changes settle on the server thread. It is not cancellable:
 * consumers validate transfers through the original Bukkit event and use this event to refresh integrations.
 * Keys are configured RPG inventory indices, not raw indices in the player's currently open container.
 */
public final class RPGInventoryEquipEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Map<Integer, ItemStack> previous;
    private final Map<Integer, ItemStack> current;
    private final Set<Integer> changedSlots;

    /** Clone both snapshots so listeners cannot mutate stored equipment or another listener's event data. */
    public RPGInventoryEquipEvent(Player player, Map<Integer, ItemStack> previous, Map<Integer, ItemStack> current) {
        super(player);
        this.previous = copy(previous);
        this.current = copy(current);
        Set<Integer> changed = new LinkedHashSet<>(previous.keySet());
        changed.addAll(current.keySet());
        changed.removeIf(slot -> java.util.Objects.equals(previous.get(slot), current.get(slot)));
        this.changedSlots = Collections.unmodifiableSet(changed);
    }

    /** Absent indices represent empty equipment slots; holders and action buttons are excluded. */
    public Map<Integer, ItemStack> getPreviousItems() { return copy(previous); }

    /** Return detached current items rather than writable references into the live inventory. */
    public Map<Integer, ItemStack> getCurrentItems() { return copy(current); }

    /** A coalesced transfer can change several slots; one event describes that final transaction. */
    public Set<Integer> getChangedSlots() { return changedSlots; }

    /** Bukkit requires the per-event handler list for synchronous dispatch. */
    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    /** Bukkit discovers this static accessor when third-party plugins register their listeners. */
    public static HandlerList getHandlerList() { return HANDLERS; }

    private static Map<Integer, ItemStack> copy(Map<Integer, ItemStack> items) {
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, ItemStack> item : items.entrySet()) {
            if (item.getValue() != null) result.put(item.getKey(), item.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }
}
