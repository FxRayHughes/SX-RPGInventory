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

package ru.endlesscode.rpginventory.event.listener;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.api.InventoryAPI;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.inventory.ActionType;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.inventory.backpack.Backpack;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackHolder;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackManager;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackUpdater;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackStorage;
import ru.endlesscode.rpginventory.inventory.slot.Slot;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import ru.endlesscode.rpginventory.utils.ItemUtils;
import ru.endlesscode.rpginventory.utils.PlayerUtils;

/**
 * Created by OsipXD on 19.10.2015
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class BackpackListener implements Listener {
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseBackpack(@NotNull PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!event.hasItem() || !ItemUtils.hasTag(item, ItemUtils.BACKPACK_TAG)) {
            return;
        }

        Player player = event.getPlayer();
        Action action = event.getAction();
        if ((action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
                && InventoryManager.isQuickSlot(player.getInventory().getHeldItemSlot())) {
            BackpackManager.open(player, item);
        }

        event.setCancelled(true);
        player.updateInventory();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBackpackClick(@NotNull final InventoryClickEvent event) {
        final Inventory inventory = event.getInventory();
        final Player player = (Player) event.getWhoClicked();

        if (!InventoryManager.playerIsLoaded(player)) {
            return;
        }

        Slot backpackSlot = SlotManager.instance().getBackpackSlot();

        if (inventory.getHolder() instanceof BackpackHolder) {
            // Number-key transfers can insert a backpack without ever using the cursor.
            if (event.getHotbarButton() >= 0 && BackpackManager.isBackpack(player.getInventory().getItem(event.getHotbarButton()))) {
                event.setCancelled(true);
                return;
            }
            // The named click was added after 1.12; comparing names avoids linking an absent enum field.
            if ("SWAP_OFFHAND".equals(event.getClick().name())
                    && BackpackManager.isBackpack(player.getInventory().getItemInOffHand())) {
                event.setCancelled(true);
                return;
            }
            // Click inside backpack
            if (BackpackManager.isBackpack(event.getCurrentItem())
                    || BackpackManager.isBackpack(event.getCursor())
                    || InventoryManager.isFilledSlot(event.getCurrentItem())
                    || InventoryManager.isFilledSlot(event.getCursor())) {
                event.setCancelled(true);
                return;
            }

            // Save changes
            if (event.getAction() == InventoryAction.NOTHING) {
                return;
            }

            BackpackUpdater.update(inventory, InventoryManager.get(player).getBackpack());
        } else if (backpackSlot != null
                && (event.getRawSlot() >= InventoryViewCompatibility.top(event.getView()).getSize()
                || event.getSlot() == backpackSlot.getSlotId()
                && InventoryAPI.isRPGInventory(event.getInventory()))
                && BackpackManager.backpackLimitReached(player)
                && BackpackManager.isBackpack(event.getCursor())
                && ActionType.getTypeOfAction(event.getAction()) == ActionType.SET) {
            // Prevent placing new backpack in bottom inventory if player can't take backpack
            int limit = BackpackManager.getLimit();
            String message = RPGInventory.getLanguage().getMessage("backpack.limit", limit);
            PlayerUtils.sendMessage(player, message);
            event.setCancelled(true);
        }
    }

    /** Dragging must preserve backpack size limits and cannot insert another portable backpack. */
    @EventHandler(ignoreCancelled = true)
    public void onBackpackDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackHolder)
                || !(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!InventoryManager.playerIsLoaded(player)) return;
        Backpack backpack = InventoryManager.get(player).getBackpack();
        if (backpack == null) { event.setCancelled(true); return; }
        for (int raw : event.getRawSlots()) {
            if (raw < event.getInventory().getSize()
                    && (raw >= backpack.getType().getSize() || BackpackManager.isBackpack(event.getOldCursor()))) {
                event.setCancelled(true);
                return;
            }
        }
        BackpackUpdater.update(event.getInventory(), backpack);
    }

    /** Closing still captures recovery bytes after lease expiry; interaction guards already prevent new edits. */
    @EventHandler
    public void onBackpackClose(@NotNull InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        Player player = (Player) event.getPlayer();

        if (!(inventory.getHolder() instanceof BackpackHolder)) {
            return;
        }

        PlayerWrapper playerWrapper = InventoryManager.get(player);
        Backpack backpack = playerWrapper.getBackpack();

        if (backpack == null) {
            return;
        }

        // Capture final contents, including drag operations, before clearing the player's open-backpack reference.
        BackpackStorage.close(backpack, java.util.Arrays.copyOf(inventory.getContents(), backpack.getType().getSize()));
        playerWrapper.setBackpack(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBackpackPickup(@NotNull EntityPickupItemEvent event) {
        if (event.getEntityType() != EntityType.PLAYER) {
            return;
        }

        Player player = (Player) event.getEntity();
        if (!InventoryManager.playerIsLoaded(player)) {
            return;
        }

        if (BackpackManager.isBackpack(event.getItem().getItemStack())
                && BackpackManager.backpackLimitReached(player)) {
            int limit = BackpackManager.getLimit();
            String message = RPGInventory.getLanguage().getMessage("backpack.limit", limit);
            PlayerUtils.sendMessage(player, message);
            event.setCancelled(true);
        }
    }
}
