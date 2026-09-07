package ru.endlesscode.rpginventory.event;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyInt;
import java.util.concurrent.atomic.AtomicInteger;

/** Consumers of the equipment API must not mutate the live item or another listener's snapshot. */
public class RPGInventoryEquipEventTest {
    @Test public void snapshotsRemainDetachedFromCallerAndListenerMutations() {
        ItemStack item = item(2);
        RPGInventoryEquipEvent event = new RPGInventoryEquipEvent(mock(Player.class),
                Collections.emptyMap(), Collections.singletonMap(10, item));
        item.setAmount(3);
        assertEquals(2, event.getCurrentItems().get(10).getAmount());
        event.getCurrentItems().get(10).setAmount(9);
        assertEquals(2, event.getCurrentItems().get(10).getAmount());
        assertThrows(UnsupportedOperationException.class, () -> event.getCurrentItems().clear());
    }

    @Test public void relocationReportsBothIndicesWhileUnchangedEquipmentHasNoChange() {
        ItemStack item = item(1);
        RPGInventoryEquipEvent event = new RPGInventoryEquipEvent(mock(Player.class),
                Collections.singletonMap(10, item), Collections.singletonMap(14, item));
        assertEquals(new HashSet<>(Arrays.asList(10, 14)), event.getChangedSlots());
        assertThrows(UnsupportedOperationException.class, () -> event.getChangedSlots().clear());
        assertTrue(new RPGInventoryEquipEvent(mock(Player.class), Collections.singletonMap(10, item),
                Collections.singletonMap(10, item)).getChangedSlots().isEmpty());
    }
    /** Model Bukkit's mutable/cloneable item contract without bootstrapping a live Paper registry. */
    private static ItemStack item(int amount) {
        AtomicInteger count = new AtomicInteger(amount);
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenAnswer(call -> count.get());
        doAnswer(call -> { count.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(call -> item(count.get()));
        return item;
    }
}
