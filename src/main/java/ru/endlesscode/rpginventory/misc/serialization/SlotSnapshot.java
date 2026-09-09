package ru.endlesscode.rpginventory.misc.serialization;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.inventory.slot.Slot;
import ru.endlesscode.rpginventory.utils.ItemUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Preserves named-group positions, purchases and complete items across storage and configuration reloads. */
public class SlotSnapshot implements ConfigurationSerializable {

    // These legacy keys form the saved group protocol; renaming them requires an explicit migration.
    private static final String SLOT_TYPE = "type";
    private static final String SLOT_BOUGHT = "bought";
    private static final String SLOT_ITEMS = "items";
    // New payload key stores complete Paper item bytes; keep 'items' readable for legacy file migration.
    private static final String SLOT_ITEM_BYTES = "item-bytes-v1";

    private final String name;
    private final String type;
    private final boolean bought;
    private final List<ItemStack> items;

    private SlotSnapshot(@NotNull String name, @NotNull String type, boolean bought, @NotNull List<ItemStack> items) {
        this.name = name;
        this.type = type;
        this.bought = bought;
        this.items = items;
    }

    @NotNull
    public static SlotSnapshot create(@NotNull Slot slot, @NotNull PlayerWrapper playerWrapper) {
        boolean bought = playerWrapper.isBuyedSlot(slot.getName());

        final Inventory inventory = playerWrapper.getInventory();
        final List<ItemStack> items = slot.getSlotIds().stream()
                .map(inventory::getItem)
                // Preserve relative positions inside a named multi-slot group, excluding UI placeholders.
                .map(stack -> ItemUtils.isNotEmpty(stack) && !slot.isCup(stack) ? stack.clone() : null)
                .collect(Collectors.toList());

        return new SlotSnapshot(slot.getName(), slot.getSlotType().name(), bought, items);
    }

    @SuppressWarnings("unused") // Should be implemented because of ConfigurationSerializable
    @NotNull
    public static SlotSnapshot deserialize(@NotNull Map<String, Object> map) {
        String type = (String) map.getOrDefault(SLOT_TYPE, "{missing}");
        boolean bought = map.containsKey(SLOT_BOUGHT);
        List<ItemStack> items = map.containsKey(SLOT_ITEM_BYTES)
                ? ItemPayloadCodec.decode((List<?>) map.get(SLOT_ITEM_BYTES))
                : (List<ItemStack>) map.getOrDefault(SLOT_ITEMS, Collections.emptyList());

        return new SlotSnapshot("", type, bought, items);
    }

    @NotNull
    @Override
    public Map<String, Object> serialize() {
        final Map<String, Object> serializedSlot = new LinkedHashMap<>();
        serializedSlot.put(SLOT_TYPE, this.type);
        serializedSlot.put(SLOT_ITEM_BYTES, ItemPayloadCodec.encode(this.items));
        if (this.bought) {
            serializedSlot.put(SLOT_BOUGHT, true);
        }

        return serializedSlot;
    }

    void restore(@NotNull PlayerWrapper playerWrapper, @NotNull Slot slot) {
        if (!slot.getSlotType().name().equals(type)) {
            // Skipping would turn a configuration mistake into permanent loss on the next autosave.
            throw new IllegalArgumentException("Saved slot type differs from configuration: " + slot.getName());
        }

        if (bought) {
            playerWrapper.setBuyedSlots(slot.getName());
        }

        final Inventory inventory = playerWrapper.getInventory();
        final List<Integer> slotIds = slot.getSlotIds();
        for (int i = slotIds.size(); i < items.size(); i++) {
            if (ItemUtils.isNotEmpty(items.get(i))) {
                throw new IllegalArgumentException("Slot size reduction would discard saved items: " + slot.getName());
            }
        }
        for (int i = 0; i < Math.min(slotIds.size(), items.size()); i++) {
            // Empty persisted positions keep their configured UI cup rather than removing the placeholder.
            if (ItemUtils.isNotEmpty(items.get(i))) inventory.setItem(slotIds.get(i), items.get(i));
        }
    }

    boolean shouldBeSaved() {
        return items.stream().anyMatch(ItemUtils::isNotEmpty) || bought;
    }

    public String getName() {
        return name;
    }
}
