package ru.endlesscode.rpginventory.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.Test;
import org.mockito.MockedStatic;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import ru.endlesscode.rpginventory.misc.FileLanguage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native mirrors must settle before integrations read them, and reload must invalidate delayed old listeners. */
public class SXAttributeBridgeTest {
    @Test public void coalescedRefreshCopiesAllNativeMirrorsBeforeIntegrationReads() {
        try (Fixture fixture = new Fixture()) {
            new SXAttributeBridge();
            SXAttributeBridge.equipmentChanged(fixture.player);
            SXAttributeBridge.equipmentChanged(fixture.player);
            assertEquals(1, fixture.tasks.size());
            fixture.tasks.get(0).run();
            assertEquals(Arrays.asList("armor", "quick", "shield", "items", "snapshot"), fixture.order);
        }
    }

    @Test public void reloadGenerationDiscardsOldPendingRefresh() {
        try (Fixture fixture = new Fixture()) {
            new SXAttributeBridge();
            SXAttributeBridge.equipmentChanged(fixture.player);
            new SXAttributeBridge();
            SXAttributeBridge.equipmentChanged(fixture.player);
            fixture.tasks.get(0).run();
            assertEquals(Collections.emptyList(), fixture.order);
            fixture.tasks.get(1).run();
            assertEquals(Arrays.asList("armor", "quick", "shield", "items", "snapshot"), fixture.order);
        }
    }

    /** Replace server-owned lifecycle services only; execute the real bridge callback and its coalescing logic. */
    private static final class Fixture implements AutoCloseable {
        final MockedStatic<RPGInventory> pluginStatic = mockStatic(RPGInventory.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<InventoryManager> inventories;
        final MockedStatic<SlotManager> slots = mockStatic(SlotManager.class);
        final MockedStatic<SXItemBridge> sxItems = mockStatic(SXItemBridge.class);
        final Player player = mock(Player.class);
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> order = new ArrayList<>();

        Fixture() {
            // InventoryManager's static title is initialized before Bukkit would normally install the plugin.
            FileLanguage language = mock(FileLanguage.class);
            when(language.getMessage("title")).thenReturn("Test inventory");
            pluginStatic.when(RPGInventory::getLanguage).thenReturn(language);
            inventories = mockStatic(InventoryManager.class);
            RPGInventory plugin = mock(RPGInventory.class);
            when(plugin.isEnabled()).thenReturn(true);
            pluginStatic.when(RPGInventory::getInstance).thenReturn(plugin);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(2L))).thenAnswer(call -> {
                tasks.add(call.getArgument(1));
                return null;
            });
            PlayerWrapper wrapper = mock(PlayerWrapper.class);
            Inventory inventory = mock(Inventory.class);
            when(wrapper.getInventory()).thenReturn(inventory);
            inventories.when(() -> InventoryManager.playerIsLoaded(player)).thenReturn(true);
            inventories.when(() -> InventoryManager.get(player)).thenReturn(wrapper);
            inventories.when(() -> InventoryManager.syncArmor(wrapper)).thenAnswer(call -> { order.add("armor"); return null; });
            inventories.when(() -> InventoryManager.syncQuickSlots(wrapper)).thenAnswer(call -> { order.add("quick"); return null; });
            inventories.when(() -> InventoryManager.syncShieldSlot(wrapper)).thenAnswer(call -> { order.add("shield"); return null; });
            sxItems.when(() -> SXItemBridge.update(eq(player), any())).thenAnswer(call -> { order.add("items"); return null; });
            SlotManager manager = mock(SlotManager.class);
            when(manager.getSlots()).thenAnswer(call -> { order.add("snapshot"); return Collections.emptyList(); });
            slots.when(SlotManager::instance).thenReturn(manager);
        }

        @Override public void close() {
            sxItems.close();
            slots.close();
            inventories.close();
            bukkit.close();
            pluginStatic.close();
        }
    }
}
