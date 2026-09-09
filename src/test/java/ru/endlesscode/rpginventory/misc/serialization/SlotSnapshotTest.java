package ru.endlesscode.rpginventory.misc.serialization;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.inventory.slot.Slot;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;

/** Config edits must not silently remove saved equipment before the next autosave. */
public class SlotSnapshotTest {
    @Test
    public void rejectsAChangedSlotType() {
        Slot slot = mock(Slot.class);
        when(slot.getName()).thenReturn("rings");
        when(slot.getSlotType()).thenReturn(Slot.SlotType.PASSIVE);
        SlotSnapshot snapshot = SlotSnapshot.deserialize(Map.of("type", "GENERIC"));
        PlayerWrapper wrapper = mock(PlayerWrapper.class);
        assertThrows(IllegalArgumentException.class, () -> snapshot.restore(wrapper, slot));
        verifyNoInteractions(wrapper);
    }

    @Test
    public void rejectsTruncationButPreservesHolesWhenSpaceRemains() {
        Slot slot = mock(Slot.class);
        when(slot.getName()).thenReturn("rings");
        when(slot.getSlotType()).thenReturn(Slot.SlotType.GENERIC);
        when(slot.getSlotIds()).thenReturn(List.of(3));
        ItemStack ring = mock(ItemStack.class);
        when(ring.getType()).thenReturn(Material.GOLD_INGOT);
        PlayerWrapper wrapper = mock(PlayerWrapper.class);
        Inventory inventory = mock(Inventory.class);
        when(wrapper.getInventory()).thenReturn(inventory);
        // A hole before the ring must remain a hole, not shift the ring into the first position.
        SlotSnapshot snapshot = SlotSnapshot.deserialize(Map.of("type", "GENERIC", "items", Arrays.asList(null, ring)));
        assertThrows(IllegalArgumentException.class, () -> snapshot.restore(wrapper, slot));
        verifyNoInteractions(inventory);
        when(slot.getSlotIds()).thenReturn(List.of(3, 7));
        snapshot.restore(wrapper, slot);
        verify(inventory).setItem(7, ring);
        verifyNoMoreInteractions(inventory);
    }
}
