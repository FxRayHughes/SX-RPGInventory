package ru.endlesscode.rpginventory.event.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.backpack.Backpack;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackStorage;

/** Freeze item transactions until storage owns a loaded inventory, including after a long server pause. */
public final class StorageGuardListener implements Listener {
    private boolean unavailable(Player player) {
        if (!InventoryManager.isAllowedWorld(player.getWorld())) return false;
        if (!InventoryManager.playerIsLoaded(player)) return true;
        Backpack backpack = InventoryManager.get(player).getBackpack();
        return backpack != null && !BackpackStorage.isActive(backpack);
    }

    /** Clicks in both halves can move persisted equipment, including shift and number-key transfers. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player && unavailable((Player) event.getWhoClicked())) event.setCancelled(true);
    }

    /** Drag transactions have an independent event and must observe the same ownership gate. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player && unavailable((Player) event.getWhoClicked())) event.setCancelled(true);
    }

    /** Dropping is a transfer out of the fenced inventory, even when no GUI is open. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (unavailable(event.getPlayer())) event.setCancelled(true);
    }

    /** Prevent item acquisition while a pending load could replace the active equipment view. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player && unavailable((Player) event.getEntity())) event.setCancelled(true);
    }

    /** Right-click equipment/backpack actions must not bypass the inventory transaction gate. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (unavailable(event.getPlayer())) event.setCancelled(true);
    }

    /** Offhand swaps are equipment transfers even outside an inventory window. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        if (unavailable(event.getPlayer())) event.setCancelled(true);
    }

    /** A player waiting for storage must not die and split ownership between vanilla drops and unloaded equipment. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && unavailable((Player) event.getEntity())) event.setCancelled(true);
    }
}
