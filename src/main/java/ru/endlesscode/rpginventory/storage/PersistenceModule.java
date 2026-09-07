package ru.endlesscode.rpginventory.storage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.backpack.Backpack;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackManager;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.utils.Log;

/** Owns persistence startup, heartbeat and shutdown; backend configuration is fixed for this plugin lifecycle. */
public final class PersistenceModule {
    private static StorageService service;
    private static BukkitTask timer;
    private PersistenceModule() { }

    /** Initialize before loading any player or portable backpack; backend failure prevents plugin activation. */
    public static void start(RPGInventory plugin) {
        // Validate before allocating a pool; invalid configuration must not leave JDBC worker threads alive.
        long leaseMillis = Math.multiplyExact(Config.getConfig().getLong("storage.lease-seconds", 60), 1000);
        if (leaseMillis < 15000) throw new IllegalArgumentException("Ownership lease must be at least 15 seconds");
        InventoryRepository repository = RepositoryFactory.open(Config.getConfig(), plugin.getDataPath());
        try {
            service = new StorageService(repository, leaseMillis,
                    plugin.getDataPath().resolve("storage/recovery"), PersistenceModule::failed);
        } catch (RuntimeException failure) {
            repository.close();
            throw failure;
        }
        long period = Math.max(20, leaseMillis / 1000 / 4 * 20);
        timer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            service.heartbeat().exceptionally(failure -> { Log.w(failure, "Storage heartbeat failed"); return null; });
            for (Player player : Bukkit.getOnlinePlayers()) InventoryManager.savePlayerInventory(player);
            BackpackManager.saveBackpacks();
        }, period, period);
    }

    /** All persistence adapters share one ordered executor, so reload and quit saves cannot overtake one another. */
    public static StorageService service() {
        if (service == null) throw new IllegalStateException("Inventory persistence is not initialized");
        return service;
    }

    /** Dispatch inventory object work to Bukkit; no Bukkit item is accessed from a storage callback thread. */
    public static void main(Runnable action) {
        RPGInventory plugin = RPGInventory.getInstance();
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    /** Called after player/backpack final snapshots are enqueued; the queue drains before the pool is closed. */
    public static void stop() {
        if (timer != null) timer.cancel();
        if (service != null) service.close();
        timer = null;
        service = null;
    }

    private static void failed(StorageSession session, Throwable failure) {
        Log.w(failure, "Persistence failure for " + session.key().value());
        if (session.isActive()) return; // A cleanup warning after a committed write does not revoke ownership.
        main(() -> {
            if (session.key().kind() == StorageKey.Kind.PLAYER) {
                Player player = Bukkit.getPlayer(session.key().id());
                if (player != null) player.kickPlayer("Your RPG inventory could not be saved safely. Please contact an administrator.");
            } else {
                // Only the current viewer is affected; other players' inventory leases remain independent.
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Backpack backpack = InventoryManager.get(player).getBackpack();
                    if (backpack != null && backpack.getUniqueId().equals(session.key().id())) {
                        player.kickPlayer("Your backpack could not be saved safely. Please contact an administrator.");
                    }
                }
            }
        });
    }
}
