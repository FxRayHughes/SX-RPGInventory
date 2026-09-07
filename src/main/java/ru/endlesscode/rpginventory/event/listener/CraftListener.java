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

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.compat.protocol.CraftInteractionListener;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.inventory.craft.CraftExtension;
import ru.endlesscode.rpginventory.inventory.craft.CraftManager;

import java.util.List;

/**
 * Created by OsipXD on 29.08.2016
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class CraftListener extends PacketAdapter {

    public CraftListener(@NotNull Plugin plugin) {
        super(plugin, PacketType.Play.Server.WINDOW_ITEMS);


    }

    @Override
    public void onPacketSending(@NotNull PacketEvent event) {
        Player player = event.getPlayer();
        if (event.isCancelled() || !InventoryManager.playerIsLoaded(player)
                || CraftInteractionListener.isExtensionsNotNeededHere(player)) {
            return;
        }

        // Copy the outbound packet before masking slots; other viewers and plugins retain their own payload.
        event.setPacket(event.getPacket().deepClone());
        if (InventoryViewCompatibility.type(player.getOpenInventory()) == InventoryType.WORKBENCH) {
            List<ItemStack> contents = new java.util.ArrayList<>(event.getPacket().getItemListModifier().read(0));

            List<CraftExtension> extensions = CraftManager.getExtensions(player);
            for (CraftExtension extension : extensions) {
                for (int slot : extension.getSlots()) {
                    contents.set(slot, extension.getCapItem());
                }
            }

            event.getPacket().getItemListModifier().write(0, contents);
        }
    }

}
