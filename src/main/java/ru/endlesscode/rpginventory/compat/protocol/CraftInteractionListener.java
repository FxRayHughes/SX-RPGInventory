/*
 * This file is part of RPGInventory.
 * Copyright (C) 2015-2017 Osip Fatkullin
 *
 * RPGInventory is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RPGInventory is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RPGInventory.  If not, see <http://www.gnu.org/licenses/>.
 */

package ru.endlesscode.rpginventory.compat.protocol;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.craft.CraftExtension;
import ru.endlesscode.rpginventory.inventory.craft.CraftManager;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.utils.PlayerUtils;

import java.util.List;

/** Pure Bukkit craft-slot transaction guards, shared by optional ProtocolLib and PacketEvents overlays. */
public final class CraftInteractionListener implements Listener {
    /** Request authoritative contents when a restricted workbench opens so the selected packet adapter can mask it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
        final Player player = (Player) event.getPlayer();
        if (!InventoryManager.playerIsLoaded(player)
                || event.getInventory().getType() != InventoryType.WORKBENCH
                || isExtensionsNotNeededHere(player)) {
            return;
        }

        //noinspection deprecation
        player.updateInventory();
    }

    /** The server rejects edits to locked raw slots even though their visible cap exists only in outgoing packets. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        final Player player = (Player) event.getWhoClicked();
        if (!InventoryManager.playerIsLoaded(player)
                || event.getInventory().getType() != InventoryType.WORKBENCH
                || isExtensionsNotNeededHere(player)) {
            return;
        }

        List<CraftExtension> extensions = CraftManager.getExtensions(player);
        for (CraftExtension extension : extensions) {
            for (int slot : extension.getSlots()) {
                if (slot == event.getRawSlot()) {
                    event.setCancelled(true);
                    PlayerUtils.updateInventory(player);
                    return;
                }
            }
        }
    }

    /** Dragging can populate several locked craft slots without triggering a click. */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!InventoryManager.playerIsLoaded(player)
                || event.getInventory().getType() != InventoryType.WORKBENCH || isExtensionsNotNeededHere(player)) return;
        for (CraftExtension extension : CraftManager.getExtensions(player)) {
            if (extension.getSlots().stream().anyMatch(event.getRawSlots()::contains)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Checks that inventory extensions not needed there.
     * It always should be used after `InventoryManager.playerIsLoaded(player)` check.
     *
     * @param player Player to check
     */
    public static boolean isExtensionsNotNeededHere(Player player) {
        return !InventoryManager.get(player).isPocketCraft()
                && !Config.getConfig().getBoolean("craft.workbench", true);
    }

    /** Restore the player's pocket-crafting state after a workbench view is closed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorkbenchClosed(@NotNull InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        if (!InventoryManager.playerIsLoaded(player)) {
            return;
        }

        if (event.getInventory().getType() == InventoryType.WORKBENCH) {
            InventoryManager.get(player).onWorkbenchClosed();
        }
    }
}
