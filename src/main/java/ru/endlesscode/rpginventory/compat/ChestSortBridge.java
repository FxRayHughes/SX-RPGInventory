package ru.endlesscode.rpginventory.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import ru.endlesscode.rpginventory.inventory.InventoryLocker;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.inventory.backpack.Backpack;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackHolder;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackStorage;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackUpdater;
import ru.endlesscode.rpginventory.inventory.slot.Slot;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;

/** Optional ChestSort event bridge; slot numbers remain the persistent equipment protocol, even when empty. */
public final class ChestSortBridge {
    private static Listener active;
    private ChestSortBridge() { }

    /** Resolve the actual ChestSort-owned event at runtime so absent optional APIs never enter core linkage. */
    public static void start(Plugin plugin) {
        stop();
        Plugin chestSort = Bukkit.getPluginManager().getPlugin("ChestSort");
        if (chestSort == null || !chestSort.isEnabled()) return;
        Listener owner = new Listener() { };
        try {
            Class<? extends Event> eventType = Class.forName("de.jeff_media.chestsort.api.ChestSortEvent", true,
                    chestSort.getClass().getClassLoader()).asSubclass(Event.class);
            Binding binding = new Binding(eventType);
            Bukkit.getPluginManager().registerEvent(eventType, owner, EventPriority.HIGHEST, (listener, event) -> {
                try {
                    if (event.isAsynchronous() || !Bukkit.isPrimaryThread()) {
                        ((Cancellable) event).setCancelled(true);
                        return;
                    }
                    Inventory inventory = (Inventory) binding.inventory.invoke(event);
                    HumanEntity actor = (HumanEntity) binding.player.invoke(event);
                    binding.apply(event, decision(inventory, actor));
                    if (!((Cancellable) event).isCancelled() && inventory.getHolder() instanceof BackpackHolder
                            && actor instanceof Player) {
                        // ChestSort runs outside Bukkit click events; capture its committed contents on the next tick.
                        Backpack backpack = InventoryManager.get((Player) actor).getBackpack();
                        if (backpack != null) BackpackUpdater.update(inventory, backpack);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    ((Cancellable) event).setCancelled(true);
                    throw new EventException(failure);
                }
            }, plugin, true);
            active = owner;
            plugin.getLogger().info("ChestSort protection enabled for fixed equipment and reserved inventory slots.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            HandlerList.unregisterAll(owner);
            // ChestSort is optional: report missing protection without blocking the player's persisted inventory load.
            plugin.getLogger().log(Level.WARNING,
                    "ChestSort protection unavailable: install a ChestSort version with ChestSortEvent#getInventory, "
                            + "getPlayer and setUnmovable(int); sorting compatibility is not active.", failure);
        }
    }

    /** Explicit cleanup also covers configuration reload, when the optional plugin remains enabled. */
    public static void stop() {
        if (active != null) HandlerList.unregisterAll(active);
        active = null;
    }

    private static Decision decision(Inventory inventory, HumanEntity actor) {
        boolean fixed = inventory.getHolder() instanceof PlayerWrapper;
        Player owner = inventory.getHolder() instanceof Player ? (Player) inventory.getHolder()
                : actor instanceof Player ? (Player) actor : null;
        boolean unavailable = owner != null && InventoryManager.isAllowedWorld(owner.getWorld())
                && !InventoryManager.playerIsLoaded(owner);
        if (inventory.getHolder() instanceof BackpackHolder) {
            Backpack backpack = owner != null && InventoryManager.playerIsLoaded(owner)
                    ? InventoryManager.get(owner).getBackpack() : null;
            unavailable |= backpack == null || !BackpackStorage.isActive(backpack)
                    // Backpack holders identify each open even when CraftBukkit replaces its inventory wrapper.
                    || InventoryViewCompatibility.top(owner.getOpenInventory()).getHolder() != inventory.getHolder();
        }
        Set<Integer> reserved = new LinkedHashSet<>();
        if (inventory instanceof PlayerInventory && owner != null && InventoryManager.playerIsLoaded(owner)) {
            for (Slot slot : SlotManager.instance().getQuickSlots()) reserved.add(slot.getQuickSlot());
            Slot shield = SlotManager.instance().getShieldSlot();
            if (shield != null) reserved.add(shield.getQuickSlot());
            // Native armor/offhand indices are never general-purpose sortable storage.
            for (int slot = 36; slot < inventory.getSize(); slot++) reserved.add(slot);
        }
        return plan(inventory, fixed || unavailable, reserved, item -> {
            if (ItemCompatibility.isEmpty(item)) return false;
            return InventoryLocker.isLockedSlot(item) || InventoryManager.isFilledSlot(item)
                    || InventoryManager.isEmptySlot(item);
        });
    }

    /** Pure decision layer lets tests exercise empty reserved slots and normal storage without a server singleton. */
    static Decision plan(Inventory inventory, boolean cancel, Collection<Integer> reserved, Predicate<ItemStack> placeholder) {
        Set<Integer> slots = new LinkedHashSet<>();
        if (!cancel) {
            for (int slot : reserved) if (slot >= 0 && slot < inventory.getSize()) slots.add(slot);
            for (int slot = 0; slot < inventory.getSize(); slot++) if (placeholder.test(inventory.getItem(slot))) slots.add(slot);
        }
        return new Decision(cancel, slots);
    }

    /** Cancellation protects whole fenced/fixed inventories; otherwise ChestSort still sorts ordinary storage slots. */
    static final class Decision {
        final boolean cancel;
        final Set<Integer> slots;
        Decision(boolean cancel, Set<Integer> slots) {
            this.cancel = cancel;
            this.slots = Collections.unmodifiableSet(new LinkedHashSet<>(slots));
        }
    }

    /** Signatures verified against the upstream ChestSort event source; tests supply only an API-shaped fixture. */
    static final class Binding {
        final Method inventory;
        final Method player;
        final Method unmovable;
        Binding(Class<? extends Event> type) throws ReflectiveOperationException {
            if (!Cancellable.class.isAssignableFrom(type)) throw new IllegalArgumentException("ChestSortEvent must be cancellable");
            inventory = type.getMethod("getInventory");
            player = type.getMethod("getPlayer");
            unmovable = type.getMethod("setUnmovable", int.class);
        }
        void apply(Event event, Decision decision) throws ReflectiveOperationException {
            if (decision.cancel) ((Cancellable) event).setCancelled(true);
            else for (int slot : decision.slots) unmovable.invoke(event, slot);
        }
    }
}
