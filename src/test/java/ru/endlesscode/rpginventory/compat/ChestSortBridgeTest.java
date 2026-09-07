package ru.endlesscode.rpginventory.compat;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** The real API contract must freeze fixed positions without freezing unrelated storage or moving any item itself. */
public class ChestSortBridgeTest {
    @Test public void emptyReservedQuickAndShieldSlotsRemainUnmovable() throws Exception {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(41);
        ChestSortBridge.Decision policy = ChestSortBridge.plan(inventory, false,
                Arrays.asList(0, 8, 40), item -> false);
        FakeChestSortEvent event = apply(inventory, policy);
        assertEquals(new LinkedHashSet<>(Arrays.asList(0, 8, 40)), event.unmovable);
        assertFalse(event.isCancelled());
        verify(inventory, never()).setContents(any());
    }

    @Test public void placeholdersArePinnedButNormalStorageStillSorts() throws Exception {
        Inventory inventory = mock(Inventory.class);
        ItemStack locked = mock(ItemStack.class);
        when(inventory.getSize()).thenReturn(36);
        when(inventory.getItem(17)).thenReturn(locked);
        FakeChestSortEvent event = apply(inventory,
                ChestSortBridge.plan(inventory, false, Collections.emptySet(), item -> item == locked));
        assertEquals(Collections.singleton(17), event.unmovable);
        assertFalse(event.unmovable.contains(9));
        assertFalse(event.isCancelled());
    }

    @Test public void fixedEquipmentOrUnavailableStorageCancelsBeforeInspectingItems() throws Exception {
        Inventory inventory = mock(Inventory.class);
        FakeChestSortEvent event = apply(inventory, ChestSortBridge.plan(inventory, true,
                Collections.emptySet(), item -> { throw new AssertionError("Must not inspect unavailable inventory"); }));
        assertTrue(event.isCancelled());
        assertTrue(event.unmovable.isEmpty());
        verifyNoInteractions(inventory);
    }

    @Test public void invalidSlotIndicesAreNotPassedIntoTheForeignApi() throws Exception {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(36);
        FakeChestSortEvent event = apply(inventory,
                ChestSortBridge.plan(inventory, false, Arrays.asList(-1, 35, 36, 40), item -> false));
        assertEquals(Collections.singleton(35), event.unmovable);
        ChestSortBridge.Binding binding = new ChestSortBridge.Binding(FakeChestSortEvent.class);
        assertSame(inventory, binding.inventory.invoke(event));
        assertNull(binding.player.invoke(event));
    }

    private static FakeChestSortEvent apply(Inventory inventory, ChestSortBridge.Decision decision) throws Exception {
        FakeChestSortEvent event = new FakeChestSortEvent(inventory);
        new ChestSortBridge.Binding(FakeChestSortEvent.class).apply(event, decision);
        return event;
    }

    /** Test-scoped representation of the verified ChestSort signatures; no foreign plugin is shaded or required. */
    public static final class FakeChestSortEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Inventory inventory;
        final Set<Integer> unmovable = new LinkedHashSet<>();
        private boolean cancelled;
        FakeChestSortEvent(Inventory inventory) { this.inventory = inventory; }
        public Inventory getInventory() { return inventory; }
        public HumanEntity getPlayer() { return null; }
        public void setUnmovable(int slot) { unmovable.add(slot); }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }
}
