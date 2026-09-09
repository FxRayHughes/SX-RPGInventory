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

package ru.endlesscode.rpginventory.utils;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.compat.PluginReporter;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.pet.PetManager;
import ru.endlesscode.rpginventory.pet.PetType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Created by OsipXD on 02.12.2015
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class EntityUtils {

    /** Keep pet movement available on Paper and Spigot through the capability-selected navigation adapter. */
    public static void goPetToPlayer(@NotNull final Player player, @NotNull final LivingEntity entity) {
        if (!InventoryManager.playerIsLoaded(player) || !player.isOnline() || entity.isDead()) {
            return;
        }

        //Issue #120, by 12 Feb 18 : https://github.com/EndlessCodeGroup/RPGInventory/issues/120#issuecomment-364834420
        if (!player.getWorld().getName().equals(entity.getWorld().getName())) {
            PetManager.teleportPet(player, null);
            return;
        }

        Location target = player.getLocation();
        final double distance = target.distance(entity.getLocation());
        if (distance > 20D && LocationUtils.isSafeLocation(target)) {
            PetManager.teleportPet(player, null);
            return;
        } else if (distance < 4D) {
            return;
        }

        PetType petType = PetManager.getPetFromEntity(entity, player);
        double speedModifier = petType == null ? 1.0 : 0.4 / petType.getSpeed();

        ru.endlesscode.rpginventory.compat.ServerCompatibility.navigate(entity, target, speedModifier);
    }
}
