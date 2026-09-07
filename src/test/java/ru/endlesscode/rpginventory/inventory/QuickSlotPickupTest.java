package ru.endlesscode.rpginventory.inventory;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.Test;
import org.mockito.MockedStatic;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.compat.ServerCompatibility;
import ru.endlesscode.rpginventory.event.listener.InventoryListener;
import ru.endlesscode.rpginventory.inventory.slot.Slot;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import ru.endlesscode.rpginventory.item.ItemManager;
import ru.endlesscode.rpginventory.misc.FileLanguage;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A ground stack is a single transfer even when several reserved quick slots accept the same item. */
public class QuickSlotPickupTest {
    @Test public void twoMatchingQuickSlotsReceiveOnlyOneGroundStack() {
        try (MockedStatic<RPGInventory> plugin = mockStatic(RPGInventory.class)) {
            // The listener uses InventoryManager's static title but does not require a running server registry.
            FileLanguage language = mock(FileLanguage.class);
            when(language.getMessage("title")).thenReturn("Test inventory");
            plugin.when(RPGInventory::getLanguage).thenReturn(language);
            try (MockedStatic<InventoryManager> inventories = mockStatic(InventoryManager.class);
                 MockedStatic<SlotManager> slots = mockStatic(SlotManager.class);
                 MockedStatic<ItemManager> items = mockStatic(ItemManager.class);
                 MockedStatic<ServerCompatibility> sound = mockStatic(ServerCompatibility.class)) {
                Player player = mock(Player.class);
                when(player.getType()).thenReturn(EntityType.PLAYER);
                PlayerInventory inventory = mock(PlayerInventory.class);
                when(player.getInventory()).thenReturn(inventory);
                ItemStack stack = mock(ItemStack.class);
                when(stack.getAmount()).thenReturn(32);
                Item groundItem = mock(Item.class);
                when(groundItem.getItemStack()).thenReturn(stack);
                inventories.when(() -> InventoryManager.playerIsLoaded(player)).thenReturn(true);
                items.when(() -> ItemManager.allowedForPlayer(player, stack, false)).thenReturn(true);
                Slot first = matchingSlot(0, stack);
                Slot second = matchingSlot(1, stack);
                SlotManager manager = mock(SlotManager.class);
                when(manager.getQuickSlots()).thenReturn(Arrays.asList(first, second));
                slots.when(SlotManager::instance).thenReturn(manager);

                // Model the two real destination slots so conservation is checked independently of the loop shape.
                Map<Integer, ItemStack> received = new HashMap<>();
                doAnswer(call -> { received.put(call.getArgument(0), call.getArgument(1)); return null; })
                        .when(inventory).setItem(anyInt(), any(ItemStack.class));
                EntityPickupItemEvent event = new EntityPickupItemEvent(player, groundItem, 0);
                new InventoryListener().onPickupToQuickSlot(event);

                assertTrue(event.isCancelled());
                assertEquals(32, received.values().stream().mapToInt(ItemStack::getAmount).sum());
                assertSame(stack, received.get(0));
                assertFalse(received.containsKey(1));
                verify(groundItem, times(1)).remove();
            }
        }
    }

    private static Slot matchingSlot(int index, ItemStack stack) {
        Slot slot = mock(Slot.class);
        when(slot.getQuickSlot()).thenReturn(index);
        when(slot.isCup(null)).thenReturn(true);
        when(slot.isValidItem(stack)).thenReturn(true);
        return slot;
    }
}
