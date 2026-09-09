package ru.endlesscode.rpginventory.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import ru.endlesscode.rpginventory.event.RPGInventoryEquipEvent;
import ru.endlesscode.rpginventory.inventory.slot.Slot;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import org.bukkit.plugin.Plugin;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.event.PlayerInventoryLoadEvent;
import ru.endlesscode.rpginventory.event.PlayerInventoryUnloadEvent;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.inventory.ArmorType;
import ru.endlesscode.rpginventory.utils.Log;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Recompute SX-Attribute's existing RPG source after committed equipment changes, without adding a duplicate source. */
public final class SXAttributeBridge implements Listener {
    private static SXAttributeBridge active;
    private final Set<UUID> pending = new HashSet<>();
    // Only server-thread access is allowed; detached snapshots avoid aliasing Bukkit's mutable ItemStacks.
    private final Map<UUID, Map<Integer, ItemStack>> equipment = new HashMap<>();

    /** A reload replaces the listener generation; already queued callbacks must not publish through the old one. */
    public SXAttributeBridge() { active = this; }

    /** Version-specific listeners use this hook without exposing their event classes to the Java 8 core. */
    public static void equipmentChanged(Player player) {
        if (active != null) active.refresh(player);
    }

    /** Loading is asynchronous now, so join-time SX scans must be followed by a refresh after actual load completion. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(PlayerInventoryLoadEvent.Post event) { refresh(event.getPlayer()); }

    /** World restrictions and unloads remove the prior RPG equipment contribution. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void unloaded(PlayerInventoryUnloadEvent.Post event) { refresh(event.getPlayer()); }

    /**
     * Armor, active and shield slots cancel the vanilla click after applying their own transaction.
     * Observe cancelled clicks too and scan the final inventory next tick; this never uncancels a denied action.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void clicked(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) refresh((Player) event.getWhoClicked());
    }

    /** Drag operations do not emit InventoryClickEvent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dragged(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player) refresh((Player) event.getWhoClicked());
    }

    /** Inventory mirrors and third-party item edits may be committed by close listeners. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) refresh((Player) event.getPlayer());
    }

    /** Broken equipment can disappear without an inventory click. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void broken(PlayerItemBreakEvent event) { refresh(event.getPlayer()); }

    /** Dropping an item changes both the held attribute source and any reserved quick-slot mirror. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dropped(PlayerDropItemEvent event) { refresh(event.getPlayer()); }

    /** F-key swaps bypass inventory clicks and can replace the mirrored shield slot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swapped(PlayerSwapHandItemsEvent event) { refresh(event.getPlayer()); }

    /** The active main-hand attribute source changes even when no inventory contents move. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { refresh(event.getPlayer()); }

    /** Only equipment use can change a mirror; ordinary block clicks must not cause an attribute scan. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void interacted(PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || ItemCompatibility.isEmpty(event.getItem())) return;
        Player player = event.getPlayer();
        if (ArmorType.matchType(event.getItem()) != ArmorType.UNKNOWN
                || InventoryManager.isQuickSlot(player.getInventory().getHeldItemSlot())) refresh(player);
    }

    /** RPG quick-slot pickup cancels vanilla pickup after committing, so observe cancelled events too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void pickedUp(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (!InventoryManager.playerIsLoaded(player)) return;
        ItemStack item = event.getItem().getItemStack();
        for (Slot slot : SlotManager.instance().getQuickSlots()) {
            if (slot.isValidItem(item)) { refresh(player); return; }
        }
    }

    /** Do not retain detached item payloads after a player leaves the server. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        equipment.remove(event.getPlayer().getUniqueId());
    }

    private void refresh(Player player) {
        // Disable unloads still remove local state, but Bukkit no longer accepts tasks for this plugin.
        if (!RPGInventory.getInstance().isEnabled()) return;
        if (!pending.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(RPGInventory.getInstance(), () -> {
            pending.remove(player.getUniqueId());
            if (active != this || !player.isOnline()) return;
            try {
                if (InventoryManager.playerIsLoaded(player)) {
                    PlayerWrapper wrapper = InventoryManager.get(player);
                    // Native equipment owns these mirrored slots; generic RPG slots remain in the RPG inventory.
                    // Waiting alone is insufficient: vanilla actions do not update the stored RPG mirror.
                    InventoryManager.syncArmor(wrapper);
                    InventoryManager.syncQuickSlots(wrapper);
                    InventoryManager.syncShieldSlot(wrapper);
                    SXItemBridge.update(player, wrapper.getInventory().getContents());
                }
                publishEquipmentChange(player);
                Plugin plugin = Bukkit.getPluginManager().getPlugin("SX-Attribute");
                if (plugin == null || !plugin.isEnabled()) return;
                Method accessor = plugin.getClass().getMethod("getAttributeManager");
                Object manager = accessor.invoke(null);
                manager.getClass().getMethod("loadEntityData", LivingEntity.class).invoke(manager, player);
                manager.getClass().getMethod("attributeUpdateEvent", LivingEntity.class).invoke(manager, player);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Log.w(failure, "SX equipment integration failed");
            }
        }, 2L); // Let vanilla commit and next-tick quick-slot placeholders settle before copying native equipment.
    }

    private void publishEquipmentChange(Player player) {
        Map<Integer, ItemStack> current = new LinkedHashMap<>();
        if (InventoryManager.playerIsLoaded(player)) {
            org.bukkit.inventory.Inventory inventory = InventoryManager.get(player).getInventory();
            for (Slot slot : SlotManager.instance().getSlots()) {
                if (slot.getSlotType() == Slot.SlotType.ACTION || slot.getSlotType() == Slot.SlotType.INFO) continue;
                for (int index : slot.getSlotIds()) {
                    ItemStack item = inventory.getItem(index);
                    if (!ItemCompatibility.isEmpty(item) && !slot.isCup(item)) current.put(index, item.clone());
                }
            }
        }
        Map<Integer, ItemStack> previous = equipment.put(player.getUniqueId(), current);
        RPGInventoryEquipEvent event = new RPGInventoryEquipEvent(player,
                previous == null ? Collections.emptyMap() : previous, current);
        // Third-party integrations receive actual changes rather than every inventory click (#155).
        if (!event.getChangedSlots().isEmpty()) Bukkit.getPluginManager().callEvent(event);
    }
}
