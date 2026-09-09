package ru.endlesscode.rpginventory.inventory;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.Test;
import org.mockito.MockedStatic;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.misc.FileLanguage;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** A fresh async load must be persisted before install, even when the player has never purchased a generic slot. */
public class NewPlayerSnapshotTest {
    @Test public void snapshotBeforeInstallationPreservesZeroOrExplicitPurchaseCount() {
        try (MockedStatic<RPGInventory> plugin = mockStatic(RPGInventory.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<SlotManager> slots = mockStatic(SlotManager.class)) {
            // InventoryManager captures its title at class initialization, before any player snapshot is created.
            FileLanguage language = mock(FileLanguage.class);
            when(language.getMessage("title")).thenReturn("Test inventory");
            plugin.when(RPGInventory::getLanguage).thenReturn(language);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), nullable(String.class)))
                    .thenReturn(mock(Inventory.class));
            SlotManager manager = mock(SlotManager.class);
            when(manager.getSlots()).thenReturn(Collections.emptyList());
            slots.when(SlotManager::instance).thenReturn(manager);
            PlayerWrapper wrapper = new PlayerWrapper(mock(OfflinePlayer.class));
            assertEquals(0, wrapper.createSnapshot().serialize().get("bought-slots"));
            wrapper.setBuyedSlots(3);
            assertEquals(3, wrapper.createSnapshot().serialize().get("bought-slots"));
        }
    }
}
