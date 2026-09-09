/*
 * This file is part of RPGInventory.
 * Copyright (C) 2015-2017 Osip Fatkullin
 *
 * RPGInventory is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RPGInventory is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RPGInventory.  If not, see <http://www.gnu.org/licenses/>.
 */

package ru.endlesscode.rpginventory;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.endlesscode.rpginventory.compat.PluginReporter;
import org.bukkit.plugin.java.JavaPlugin;
// Use Bukkit scheduling directly; the old wrapper implements an obsolete Plugin interface.
import org.bukkit.scheduler.BukkitRunnable;
import ru.endlesscode.rpginventory.compat.OptionalMimicBridge;
import ru.endlesscode.rpginventory.compat.VersionHandler;
import ru.endlesscode.rpginventory.compat.SXAttributeBridge;
import ru.endlesscode.rpginventory.compat.ChestSortBridge;
import ru.endlesscode.rpginventory.compat.OptionalMyPetBridge;
import ru.endlesscode.rpginventory.event.listener.*;
import ru.endlesscode.rpginventory.inventory.InventoryLocker;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackManager;
import ru.endlesscode.rpginventory.inventory.craft.CraftManager;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import ru.endlesscode.rpginventory.item.ItemManager;
import ru.endlesscode.rpginventory.misc.FileLanguage;
import ru.endlesscode.rpginventory.misc.Updater;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.misc.config.ConfigUpdater;
import ru.endlesscode.rpginventory.misc.serialization.Serialization;
import ru.endlesscode.rpginventory.storage.PersistenceModule;
import ru.endlesscode.rpginventory.pet.PetManager;
import ru.endlesscode.rpginventory.resourcepack.ResourcePackModule;
import ru.endlesscode.rpginventory.utils.Log;
import ru.endlesscode.rpginventory.utils.PlayerUtils;
import ru.endlesscode.rpginventory.utils.StringUtils;
import ru.endlesscode.rpginventory.utils.Version;

import java.nio.file.Path;

/** Bukkit owns the real plugin lifecycle on every server, avoiding version-specific Plugin proxy methods. */
public class RPGInventory extends JavaPlugin {
    private static RPGInventory instance;

    private final PluginReporter reporter = new PluginReporter();

    /** Central logger retained for subsystem diagnostics without an external lifecycle wrapper. */
    public PluginReporter getReporter() { return reporter; }

    private Permission perms;
    private Economy economy;

    private FileLanguage language;
    private boolean placeholderApiHooked = false;
    private boolean myPetHooked = false;
    private ResourcePackModule resourcePackModule = null;

    public static RPGInventory getInstance() {
        return instance;
    }

    public static FileLanguage getLanguage() {
        return instance.language;
    }

    public static Permission getPermissions() {
        return instance.perms;
    }

    public static Economy getEconomy() {
        return instance.economy;
    }

    @Contract(pure = true)
    public static boolean economyConnected() {
        return instance.economy != null;
    }

    @Contract(pure = true)
    public static boolean isPlaceholderApiHooked() {
        return instance.placeholderApiHooked;
    }

    @Contract(pure = true)
    public static boolean isMyPetHooked() {
        return instance.myPetHooked;
    }

    @Nullable
    public static ResourcePackModule getResourcePackModule() {
        return instance.resourcePackModule;
    }

    /** Initialize shared services only after Bukkit has attached this JavaPlugin instance. */
    public void init() {
        instance = this;
        Log.init(this.getLogger());
        Config.init(this);
    }

    @Override
    public void onLoad() {
        init();
        // Keep optional API types out of this class: legacy JVM verification can resolve them before onLoad.
        OptionalMimicBridge.load(this);
    }

    @Override
    public void onEnable() {
        OptionalMimicBridge.enable(this);

        loadConfigs();
        Serialization.registerTypes();
        // Select one authoritative backend before any inventory load; never silently fall back on connection failure.
        try {
            PersistenceModule.start(this);
        } catch (RuntimeException failure) {
            Log.w(failure, "Cannot initialize SX-RPGInventory storage");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        hookPlaceholderApi();
        if (!loadModules()) {
            getPluginLoader().disablePlugin(this);
            return;
        }
        loadPlayers();
        startMetrics();

        // Enable commands
        this.getCommand("rpginventory")
                .setExecutor(new RPGInventoryCommandExecutor());

        this.checkUpdates(null);
    }

    private void hookPlaceholderApi() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            StringUtils.Placeholders.registerPlaceholders();
            placeholderApiHooked = true;
            Log.i("Placeholder API hooked!");
        } else {
            placeholderApiHooked = false;
        }
    }

    void reload() {
        // Unload
        saveData();
        // Replacing slot/type definitions could make the only unserialized copy impossible to recover.
        if (InventoryManager.hasRetainedState()
                || ru.endlesscode.rpginventory.inventory.backpack.BackpackStorage.hasRetainedState()) {
            Log.w("Reload aborted: inventory snapshots failed; retained live data must be inspected first.");
            loadPlayers();
            return;
        }
        removeListeners();

        // Load
        loadConfigs();
        if (!loadModules()) {
            getPluginLoader().disablePlugin(this);
            return;
        }
        loadPlayers();
    }

    private void loadConfigs() {
        this.updateConfig();
        Config.reload();
        language = new FileLanguage(this);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean loadModules() {
        if (!checkRequirements()) {
            return false;
        }

        PluginManager pm = getServer().getPluginManager();

        // Hook MyPet
        if (pm.isPluginEnabled("MyPet") && OptionalMyPetBridge.init(this)) {
            myPetHooked = true;
            Log.i("MyPet used as pet system");
        } else {
            myPetHooked = false;
            Log.i(PetManager.init(this) ? "Pet system is enabled" : "Pet system isn''t loaded");
        }

        // Load modules
        Log.i(CraftManager.init(this) ? "Craft extensions is enabled" : "Craft extensions isn''t loaded");
        Log.i(InventoryLocker.init(this) ? "Inventory lock system is enabled" : "Inventory lock system isn''t loaded");
        Log.i(ItemManager.init(this) ? "Item system is enabled" : "Item system isn''t loaded");
        Log.i(BackpackManager.init(this) ? "Backpack system is enabled" : "Backpack system isn''t loaded");

        // Registering other listeners
        pm.registerEvents(new ArmorEquipListener(), this);
        pm.registerEvents(new HandSwapListener(), this);
        pm.registerEvents(new PlayerListener(), this);
        pm.registerEvents(new WorldListener(), this);
        // Re-register on configuration reload too; removeListeners clears all listeners owned by this plugin.
        pm.registerEvents(new StorageGuardListener(), this);
        pm.registerEvents(new SXAttributeBridge(), this);
        ChestSortBridge.start(this);

        if (SlotManager.instance().getElytraSlot() != null) {
            pm.registerEvents(new ElytraListener(), this);
        }
        this.resourcePackModule = ResourcePackModule.init(this);

        return true;
    }

    private void removeListeners() {
        ChestSortBridge.stop();
        // The chosen adapter owns packet registration; core must not resolve either optional packet API.
        CraftManager.stop();
        HandlerList.unregisterAll(this);
    }

    private boolean checkRequirements() {
        // Check if plugin is enabled
        if (!Config.getConfig().getBoolean("enabled")) {
            Log.w("RPGInventory is disabled in the config!");
            return false;
        }

        // Check version compatibility
        if (VersionHandler.isNotSupportedVersion()) {
            Log.w("This version of RPG Inventory is not tested with \"{0}\"!", Bukkit.getBukkitVersion());
        } else if (VersionHandler.isExperimentalSupport()) {
            Log.w("Support of {0} is experimental! Use RPGInventory with caution.", Bukkit.getBukkitVersion());
        }

        // Check dependencies
        if (this.setupPermissions()) {
            Log.i("Permissions hooked: {0}", perms.getName());
        } else {
            Log.s("Permissions not found!");
            return false;
        }

        if (this.setupEconomy()) {
            Log.i("Economy hooked: {0}", economy.getName());
        } else {
            Log.w("Economy not found!");
        }

        return InventoryManager.init(this) && SlotManager.init();
    }

    @Override
    public void onDisable() {
        ChestSortBridge.stop();
        CraftManager.stop();
        // Loading the expansion subclass without PlaceholderAPI also fails during early startup shutdown.
        if (placeholderApiHooked) {
            StringUtils.Placeholders.unregisterPlaceholders();
            placeholderApiHooked = false;
        }
        saveData();
        // Final snapshots are enqueued by saveData before the worker/pool are drained.
        PersistenceModule.stop();
    }

    private void saveData() {
        // Async opens created before reload must not use backpack definitions after they have been replaced.
        ru.endlesscode.rpginventory.inventory.backpack.BackpackStorage.cancelPendingLoads();
        BackpackManager.saveBackpacks();
        this.savePlayers();
    }

    private void startMetrics() {
        new Metrics(this, 4210);
    }

    private void savePlayers() {
        if (this.getServer().getOnlinePlayers().size() == 0) {
            return;
        }

        Log.i("Saving players inventories...");
        for (Player player : this.getServer().getOnlinePlayers()) {
            InventoryManager.unloadPlayerInventory(player);
        }
    }

    private void loadPlayers() {
        if (this.getServer().getOnlinePlayers().size() == 0) {
            return;
        }

        Log.i("Loading players inventories...");
        for (Player player : this.getServer().getOnlinePlayers()) {
            InventoryManager.loadPlayerInventory(player);
        }
    }

    private boolean setupPermissions() {
        RegisteredServiceProvider<Permission> permissionProvider = getServer().getServicesManager().getRegistration(Permission.class);
        if (permissionProvider != null) {
            perms = permissionProvider.getProvider();
        }

        return perms != null;
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        }

        return economy != null;
    }

    public void checkUpdates(@Nullable final Player player) {
        if (!Config.getConfig().getBoolean("check-update")) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                Updater updater = new Updater(RPGInventory.instance, Updater.UpdateType.NO_DOWNLOAD);
                if (updater.getResult() == Updater.UpdateResult.UPDATE_AVAILABLE) {
                    String[] lines = {
                            StringUtils.coloredLine("&3=================&b[&eRPGInventory&b]&3==================="),
                            StringUtils.coloredLine("&6New version available: &a" + updater.getLatestName() + "&6!"),
                            StringUtils.coloredLine(updater.getDescription()),
                            StringUtils.coloredLine("&6Changelog: &e" + updater.getInfoLink()),
                            StringUtils.coloredLine("&6Download it on &eSpigotMC&6!"),
                            StringUtils.coloredLine("&3==================================================")
                    };

                    for (String line : lines) {
                        if (player == null) {
                            StringUtils.coloredConsole(line);
                        } else {
                            PlayerUtils.sendMessage(player, line);
                        }
                    }
                }
            }
        }.runTaskAsynchronously(RPGInventory.getInstance());
    }

    private void updateConfig() {
        final Version version = Version.parseVersion(this.getDescription().getVersion());

        if (!Config.getConfig().contains("version")) {
            Config.getConfig().set("version", version.toString());
            Config.save();
            return;
        }

        final Version configVersion = Version.parseVersion(Config.getConfig().getString("version"));
        if (version.compareTo(configVersion) > 0) {
            ConfigUpdater.update(configVersion);
            Config.getConfig().set("version", null);
            Config.getConfig().set("version", version.toString());
            Config.save();
        }
    }

    @NotNull
    public Path getDataPath() {
        return getDataFolder().toPath();
    }
}
