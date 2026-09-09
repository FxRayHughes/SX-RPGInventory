package ru.endlesscode.rpginventory.compat;

import org.junit.Test;
import ru.endlesscode.rpginventory.compat.protocol.CraftPacketState;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Network-thread decisions must stay scoped to the captured inventory and fail closed after a server pause. */
public class CraftPacketStateTest {
    @Test public void overlayNeverTouchesOtherWindowsOrCursorPackets() {
        CraftPacketState<String> state = snapshot(false, true);
        assertEquals("locked", state.capsFor(7, 7).get(8));
        assertTrue(state.capsFor(7, 6).isEmpty());
        assertTrue(state.capsFor(7, 0).isEmpty());
        assertTrue(state.capsFor(7, -1).isEmpty());
        assertTrue(state.capsFor(7, -2).isEmpty());
        assertTrue(state.capsFor(0, 0).isEmpty());
    }

    @Test public void nonWorkbenchScreensHaveNoOverlay() {
        assertTrue(snapshot(false, false).capsFor(7, 7).isEmpty());
    }

    @Test public void delayedNetworkRecipeCannotUseStaleAuthorization() {
        CraftPacketState<String> state = snapshot(false, true);
        assertFalse(state.blocksRecipe(100));
        assertFalse(state.blocksRecipe(1000000100L));
        assertTrue(state.blocksRecipe(1000000101L));
        assertTrue(snapshot(true, true).blocksRecipe(100));
    }

    @Test public void publishedSlotMapCannotBeChangedByProducerOrConsumer() {
        Map<Integer, String> original = new LinkedHashMap<>();
        original.put(8, "locked");
        CraftPacketState<String> state = new CraftPacketState<>(true, true, 100, original);
        original.clear();
        assertEquals("locked", state.capsFor(7, 7).get(8));
        assertThrows(UnsupportedOperationException.class, () -> state.capsFor(7, 7).put(1, "forged"));
    }

    @Test public void configurationCannotMaskResultOrPlayerInventorySlots() {
        for (int forbidden : new int[] {-1, 0, 10, 36}) {
            Map<Integer, String> slots = new LinkedHashMap<>();
            slots.put(forbidden, "locked");
            assertThrows(IllegalArgumentException.class, () -> new CraftPacketState<>(true, true, 100, slots));
        }
    }

    private static CraftPacketState<String> snapshot(boolean blocked, boolean workbench) {
        Map<Integer, String> slots = new LinkedHashMap<>();
        slots.put(8, "locked");
        return new CraftPacketState<>(blocked, workbench, 100, slots);
    }
}
