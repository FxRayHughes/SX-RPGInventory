package ru.endlesscode.rpginventory.misc.serialization;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.inventory.slot.Slot;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Named equipment groups and purchase state; restoration must not silently drop groups removed from config. */
public class InventorySnapshot implements ConfigurationSerializable {

    // Persistent keys retain upstream spelling so gzip YAML migration preserves purchased capacity.
    private static final String INV_SLOTS = "slots";
    private static final String INV_BOUGHT_SLOTS = "bought-slots";

    private final Map<String, SlotSnapshot> slots;
    private final int boughtSlots;


    private InventorySnapshot(@NotNull Map<String, SlotSnapshot> slots, int boughtSlots) {
        this.slots = slots;
        this.boughtSlots = boughtSlots;
    }

    @NotNull
    public static InventorySnapshot create(@NotNull PlayerWrapper playerWrapper) {
        final Map<String, SlotSnapshot> slots = SlotManager.instance().getSlots().stream()
                .filter(slot -> slot.getSlotType() != Slot.SlotType.ARMOR)
                .map(slot -> SlotSnapshot.create(slot, playerWrapper))
                .filter(SlotSnapshot::shouldBeSaved)
                .collect(Collectors.toMap(SlotSnapshot::getName, Function.identity()));

        return new InventorySnapshot(slots, playerWrapper.getBuyedGenericSlots());
    }

    @SuppressWarnings("unused") // Should be implemented because of ConfigurationSerializable
    @NotNull
    public static InventorySnapshot deserialize(@NotNull Map<String, Object> map) {
        final Map<String, SlotSnapshot> slots = (Map<String, SlotSnapshot>) map.getOrDefault(INV_SLOTS, Collections.emptyMap());
        int boughtSlots = (Integer) map.getOrDefault(INV_BOUGHT_SLOTS, 0);

        return new InventorySnapshot(slots, boughtSlots);
    }

    @NotNull
    @Override
    public Map<String, Object> serialize() {
        final Map<String, Object> serializedInventory = new LinkedHashMap<>();

        serializedInventory.put(INV_BOUGHT_SLOTS, boughtSlots);
        serializedInventory.put(INV_SLOTS, slots);

        return serializedInventory;
    }

    /** Reject incompatible configuration before installing a live inventory that could overwrite the saved record. */
    public PlayerWrapper restore(@NotNull Player player) {
        java.util.Set<String> configured = SlotManager.instance().getSlots().stream()
                .map(Slot::getName).collect(Collectors.toSet());
        for (String name : slots.keySet()) {
            if (!configured.contains(name)) {
                throw new IllegalArgumentException("Saved inventory references missing slot: " + name);
            }
        }
        final PlayerWrapper playerWrapper = new PlayerWrapper(player);
        playerWrapper.setBuyedSlots(boughtSlots);

        SlotManager.instance().getSlots().stream()
                .filter(slot -> slots.containsKey(slot.getName()))
                .forEach(slot -> slots.get(slot.getName()).restore(playerWrapper, slot));

        return playerWrapper;
    }
}
