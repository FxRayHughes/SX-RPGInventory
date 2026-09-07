package ru.endlesscode.rpginventory.probe;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One-shot #152 regression trigger; normal Bukkit quit/close listeners remain solely responsible for persistence. */
public final class KickAfterDeposit implements Listener {
    private static final Map<UUID, KickAfterDeposit> ARMED = new HashMap<>();
    private final Player player;
    // CraftBukkit may expose different Inventory wrappers for one open session; its unique holder is stable.
    private final BackpackHolder holder;
    private final String run;
    private final int size;
    private final Plugin plugin;
    private BukkitTask expiry;

    private KickAfterDeposit(Player player, Inventory inventory, String run, int size, Plugin plugin) {
        this.player = player; this.holder = (BackpackHolder) inventory.getHolder();
        this.run = run; this.size = size; this.plugin = plugin;
    }

    /** Bind to exactly the authorized test player's current backpack; another window or player's clicks cannot trigger it. */
    public static void arm(Player player, String run) {
        Inventory inventory = InventoryViewCompatibility.top(player.getOpenInventory());
        if (!(inventory.getHolder() instanceof BackpackHolder) || InventoryManager.get(player).getBackpack() == null) {
            throw new IllegalStateException("open the target backpack before arming kick");
        }
        if (ARMED.containsKey(player.getUniqueId())) throw new IllegalStateException("kick is already armed for this player");
        Plugin plugin = Bukkit.getPluginManager().getPlugin("SX-RPGInventory-Probe");
        KickAfterDeposit armed = new KickAfterDeposit(player, inventory, run,
                InventoryManager.get(player).getBackpack().getType().getSize(), plugin);
        ARMED.put(player.getUniqueId(), armed);
        Bukkit.getPluginManager().registerEvents(armed, plugin);
        // A forgotten arm cannot kick an unrelated future session; close/quit also detach immediately.
        armed.expiry = Bukkit.getScheduler().runTaskLater(plugin, armed::disarm, 600L);
    }

    /** MONITOR observes a valid committed transaction; next tick is before BackpackUpdater's two-tick delayed copy. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void deposited(InventoryClickEvent event) {
        if (event.getWhoClicked() != player || event.getInventory().getHolder() != holder || event.getRawSlot() < 0
                || event.getRawSlot() >= size || !isPayload(event.getCursor())) return;
        InventoryAction action = event.getAction();
        if (action != InventoryAction.PLACE_ALL && action != InventoryAction.PLACE_ONE && action != InventoryAction.PLACE_SOME) return;
        int slot = event.getRawSlot();
        disarm();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Never manufacture the mutation or save here: verify Bukkit really applied the click, then issue a real kick.
            Inventory current = InventoryViewCompatibility.top(player.getOpenInventory());
            if (player.isOnline() && current != null && current.getHolder() == holder
                    && isPayload(current.getItem(slot))) {
                plugin.getLogger().info("SXRPG_E2E_KICK executed player=" + player.getName() + " run=" + run + " slot=" + slot);
                player.kickPlayer("SXRPG_E2E_KICK:" + run);
            } else {
                plugin.getLogger().warning("SXRPG_E2E_KICK not executed: committed target payload was not found run=" + run);
            }
        }, 1L);
    }

    /** Do not keep an armed listener after its specific view has gone away. */
    @EventHandler
    public void closed(InventoryCloseEvent event) {
        if (event.getPlayer() == player && event.getInventory().getHolder() == holder) disarm();
    }

    /** Normal disconnect is cleanup only; no persistence is performed from this test listener. */
    @EventHandler
    public void quit(PlayerQuitEvent event) {
        if (event.getPlayer() == player) disarm();
    }

    private boolean isPayload(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                && ("SXRPG:" + run + ":payload").equals(item.getItemMeta().getDisplayName());
    }

    private void disarm() {
        ARMED.remove(player.getUniqueId(), this);
        HandlerList.unregisterAll(this);
        if (expiry != null) expiry.cancel();
    }
}
