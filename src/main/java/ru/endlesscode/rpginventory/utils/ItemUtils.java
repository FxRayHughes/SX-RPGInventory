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

import org.bukkit.Material;
import ru.endlesscode.rpginventory.compat.ItemCompatibility;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackManager;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackType;
import ru.endlesscode.rpginventory.item.CustomItem;
import ru.endlesscode.rpginventory.item.ItemManager;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.misc.config.TexturesType;
import ru.endlesscode.rpginventory.pet.PetFood;
import ru.endlesscode.rpginventory.pet.PetManager;
import ru.endlesscode.rpginventory.pet.PetType;

/**
 * Created by OsipXD on 28.08.2015
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class ItemUtils {
    public static final String ENTITY_TAG = "EntityTag";

    public static final String BACKPACK_UID_TAG = "backpack.uid";
    public static final String BACKPACK_TAG = "backpack.id";
    public static final String ITEM_TAG = "rpginv.id";
    public static final String FOOD_TAG = "food.id";
    public static final String PET_TAG = "pet.id";

    /** The compatibility layer preserves unrelated metadata while using PDC or the legacy literal NBT key. */
    @NotNull
    public static ItemStack setTag(ItemStack item, @NotNull String tag, @NotNull String value) {
        if (isEmpty(item)) return item;
        ItemCompatibility.setString(item, tag, value);
        return item;
    }

    /** Read the same persistent identity on both PDC-capable and pre-PDC servers. */
    @NotNull
    public static String getTag(@NotNull ItemStack item, @NotNull String tag) {
        return getTag(item, tag, "");
    }

    /** Missing tags use the caller's default; a present identity must not be inferred from display text. */
    @NotNull
    public static String getTag(@NotNull ItemStack item, @NotNull String tag, @NotNull String defaultValue) {
        if (isEmpty(item)) return defaultValue;
        return ItemCompatibility.getString(item, tag, defaultValue);
    }

    /** Presence is checked using the same reader as identity resolution, including legacy imports. */
    @Contract("null, _ -> false")
    public static boolean hasTag(@Nullable ItemStack item, String tag) {
        return isNotEmpty(item) && !getTag(item, tag).isEmpty();
    }

    public static boolean isItemHasDurability(ItemStack item) {
        return item.getType().getMaxDurability() > 0;
    }

    @NotNull
    public static ItemStack[] syncItems(ItemStack[] items) {
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemUtils.syncItem(items[i]);
        }

        return items;
    }

    @NotNull
    @Contract("null -> !null")
    private static ItemStack syncItem(@Nullable ItemStack item) {
        if (ItemUtils.isEmpty(item)) {
            return new ItemStack(Material.AIR);
        }

        int itemTextureData = getTextureData(item);
        int amount = item.getAmount();
        int foundTextureData;
        if (CustomItem.isCustomItem(item)) {
            CustomItem custom = ItemManager.getCustomItem(item);

            if (custom == null) {
                return new ItemStack(Material.AIR);
            }

            foundTextureData = custom.getTextureData();
            item = ItemManager.getItem(ItemUtils.getTag(item, ItemUtils.ITEM_TAG));
        } else if (BackpackManager.isBackpack(item)) {
            BackpackType type = BackpackManager.getBackpackType(ItemUtils.getTag(item, ItemUtils.BACKPACK_TAG));

            if (type == null) {
                return new ItemStack(Material.AIR);
            }

            foundTextureData = type.getTextureData();

            String bpUID = ItemUtils.getTag(item, ItemUtils.BACKPACK_UID_TAG);
            if (!bpUID.isEmpty()) {
                ItemUtils.setTag(item, ItemUtils.BACKPACK_UID_TAG, bpUID);
            }
        } else if (PetType.isPetItem(item)) {
            PetType petType = PetManager.getPetFromItem(item);
            if (petType == null) {
                return new ItemStack(Material.AIR);
            }
            foundTextureData = petType.getTextureData();

            long deathTime = PetManager.getDeathTime(item);
            double health = PetManager.getHealth(item, petType.getHealth());

            item = petType.getSpawnItem();
            PetManager.saveDeathTime(item, deathTime);
            PetManager.saveHealth(item, health);
        } else if (PetFood.isFoodItem(item)) {
            PetFood food = PetManager.getFoodFromItem(item);
            if (food == null) {
                return new ItemStack(Material.AIR);
            }
            foundTextureData = food.getTextureData();

            item = food.getFoodItem();
        } else {
            return item;
        }

        setTextureData(item, foundTextureData == -1 ? itemTextureData : foundTextureData);
        item.setAmount(amount);
        return item;
    }

    /** Resolve texture metadata through version-aware APIs; old damage predicates remain readable. */
    public static int getTextureData(@NotNull ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return 0;
        }

        return Config.texturesType == TexturesType.DAMAGE
                ? ItemCompatibility.getDamage(itemStack) : ItemCompatibility.getCustomModelData(itemStack);
    }

    private static void setTextureData(@NotNull ItemStack itemStack, int data) {
        // Each helper commits its own meta, avoiding a stale second setItemMeta that erases the change.
        if (Config.texturesType == TexturesType.DAMAGE) ItemCompatibility.setDamage(itemStack, data);
        else ItemCompatibility.setCustomModelData(itemStack, data);
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable ItemStack item) {
        return ItemCompatibility.isEmpty(item);
    }

    @Contract("null -> false")
    public static boolean isNotEmpty(@Nullable ItemStack item) {
        return !ItemCompatibility.isEmpty(item);
    }

    @NotNull
    public static ItemStack toBukkitItemStack(ItemStack item) {
        // Modern Bukkit ItemStack already exposes all required metadata APIs; no NMS wrapper is necessary.
        return item;
    }
}
