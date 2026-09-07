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

package ru.endlesscode.rpginventory.inventory.backpack;

import org.bukkit.Bukkit;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.misc.serialization.ItemPayloadCodec;
import ru.endlesscode.rpginventory.utils.Log;

import java.util.*;

/**
 * Created by OsipXD on 19.10.2015
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class Backpack implements ConfigurationSerializable {

    private static final String BP_ID = "id";
    private static final String BP_TYPE = "type";
    private static final String BP_CONTENTS = "contents";
    // Versioned full item payloads replace lossy Bukkit YAML item maps; old contents stay readable.
    private static final String BP_ITEM_BYTES = "item-bytes-v1";
    private static final String BP_LAST_USE = "last-use";

    private final UUID id;
    @NotNull
    private final BackpackType backpackType;

    private long lastUse;
    private ItemStack[] contents;

    public Backpack(@NotNull BackpackType backpackType) {
        this(backpackType, UUID.randomUUID());
    }

    public Backpack(@NotNull BackpackType backpackType, UUID uuid) {
        this.id = uuid;
        this.backpackType = backpackType;
        this.contents = new ItemStack[backpackType.getSize()];
    }

    @SuppressWarnings("unused") // Should be implemented because of ConfigurationSerializable
    @NotNull
    public static Backpack deserialize(@NotNull Map<String, Object> map) {
        UUID id = UUID.fromString((String) map.get(BP_ID));
        BackpackType type = BackpackManager.getBackpackType((String) map.get(BP_TYPE));
        if (type == null) throw new IllegalArgumentException("Unknown saved backpack type: " + map.get(BP_TYPE));
        List<ItemStack> contents = map.containsKey(BP_ITEM_BYTES)
                ? ItemPayloadCodec.decode((List<?>) map.get(BP_ITEM_BYTES))
                : (List<ItemStack>) map.getOrDefault(BP_CONTENTS, Collections.emptyList());
        long lastUse = ((Number) map.getOrDefault(BP_LAST_USE, 0L)).longValue();

        Backpack backpack = new Backpack(type, id);
        backpack.setContents(contents.toArray(new ItemStack[0]));
        backpack.setLastUse(lastUse);

        return backpack;
    }

    @NotNull
    @Override
    public Map<String, Object> serialize() {
        final Map<String, Object> serializedBackpack = new LinkedHashMap<>();
        serializedBackpack.put(BP_ID, this.id.toString());
        serializedBackpack.put(BP_TYPE, this.backpackType.getId());
        serializedBackpack.put(BP_ITEM_BYTES, ItemPayloadCodec.encode(Arrays.asList(this.contents)));
        serializedBackpack.put(BP_LAST_USE, this.lastUse);

        return serializedBackpack;
    }

    /** Persistent UUID is independent of type name and current carrier; it is the backend ownership key. */
    public UUID getUniqueId() {
        return this.id;
    }

    @NotNull
    public BackpackType getType() {
        return backpackType;
    }

    /** Report cancelled/replaced opens so the storage adapter can release an unused ownership lease. */
    boolean open(@NotNull Player player) {
        int realSize = (int) Math.ceil(this.backpackType.getSize() / 9.0) * 9;
        if (realSize > 54) {
            realSize = 54;
            Log.w("Backpack can''t contain more than 54 slots. So it was trimmed downto 54.");
        }
        BackpackHolder holder = new BackpackHolder();
        Inventory inventory = Bukkit.createInventory(holder, realSize, backpackType.getTitle());
        holder.setInventory(inventory);

        for (int i = 0; i < realSize; i++) {
            if (i < this.backpackType.getSize()) {
                if (this.contents[i] != null) {
                    inventory.setItem(i, this.contents[i]);
                }
            } else {
                inventory.setItem(i, InventoryManager.getFillSlot());
            }
        }

        try {
            player.openInventory(inventory);
        } catch (RuntimeException failure) {
            // A listener may throw after opening the view; never leave a writable view without its owner wrapper.
            if (InventoryViewCompatibility.top(player.getOpenInventory()).getHolder() == holder) player.closeInventory();
            throw failure;
        }
        // CraftBukkit can wrap the same native inventory in a different Bukkit object when opening it.
        // This holder is unique to this open: it survives wrapping but rejects cancelled or redirected views.
        if (InventoryViewCompatibility.top(player.getOpenInventory()).getHolder() != holder) return false;
        InventoryManager.get(player).setBackpack(this);
        return true;
    }

    public void setContents(ItemStack[] contents) {
        // Pad expanded backpack types, but reject shrinking over nonempty slots instead of deleting items.
        int size = this.backpackType.getSize();
        for (int i = size; i < contents.length; i++) {
            // Air variants arrived after 1.12; names preserve modern semantics without newer Material APIs.
            String material = contents[i] == null ? "AIR" : contents[i].getType().name();
            if (!"AIR".equals(material) && !"CAVE_AIR".equals(material) && !"VOID_AIR".equals(material)) {
                throw new IllegalArgumentException("Backpack size reduction would discard saved items");
            }
        }
        this.contents = new ItemStack[size];
        for (int i = 0; i < Math.min(size, contents.length); i++) {
            this.contents[i] = contents[i] == null ? null : contents[i].clone();
        }
    }

    public void onUse() {
        this.lastUse = System.currentTimeMillis();
    }

    public void setLastUse(long lastUse) {
        this.lastUse = lastUse;
    }

    boolean isOverdue() {
        int lifeTime = Config.getConfig().getInt("backpacks.expiration-time", 0);
        return lifeTime != 0 && (System.currentTimeMillis() - this.lastUse) / (1_000 * 60 * 60 * 24) > lifeTime;
    }
}
