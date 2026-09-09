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

package ru.endlesscode.rpginventory.inventory.craft;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.compat.VersionHandler;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.item.Texture;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.utils.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Created by OsipXD on 29.08.2016
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class CraftManager {
    @NotNull
    private static final List<CraftExtension> EXTENSIONS = new ArrayList<>();
    private static Texture textureOfExtendable;
    private static Runnable packetCleanup;

    private CraftManager() {
    }

    /** Enable craft restrictions only when one optional packet provider can enforce the complete slot policy. */
    public static boolean init(@NotNull RPGInventory instance) {
        stop();
        MemorySection config = (MemorySection) Config.getConfig().get("craft");

        if (config == null) {
            Log.w("Section ''craft'' not found in config.yml");
            return false;
        }

        if (!config.getBoolean("enabled")) {
            Log.i("Craft system is disabled in config");
            return false;
        }

        if (!instance.getServer().getPluginManager().isPluginEnabled("ProtocolLib")
                && !instance.getServer().getPluginManager().isPluginEnabled("packetevents")) {
            Log.w("Craft extensions require PacketEvents or ProtocolLib supporting this server version.");
            return false;
        }
        try {
            Texture texture = Texture.parseTexture(config.getString("extendable"));
            if (texture.isEmpty()) {
                Log.s("Invalid texture in ''craft.extendable''");
                return false;
            }
            textureOfExtendable = texture;

            @Nullable final ConfigurationSection extensions = config.getConfigurationSection("extensions");
            if (extensions == null) {
                Log.s("Section ''craft.extensions'' not found in config.yml");
                return false;
            }

            EXTENSIONS.clear();
            for (String extensionName : extensions.getKeys(false)) {
                EXTENSIONS.add(new CraftExtension(extensionName, extensions.getConfigurationSection(extensionName)));
            }

            // Modern protocols prefer PacketEvents; neither provider's API may enter core method signatures.
            boolean modern = VersionHandler.getVersionCode() >= 1_20_05;
            return (modern ? registerProvider(instance, "packetevents") : registerProvider(instance, "ProtocolLib"))
                    || (modern ? registerProvider(instance, "ProtocolLib") : registerProvider(instance, "packetevents"));
        } catch (Exception e) {
            instance.getReporter().report("Error on CraftManager initialization", e);
            return false;
        }
    }

    /** Detach the chosen provider on reload/disable without resolving any optional packet classes in core code. */
    public static void stop() {
        Runnable cleanup = packetCleanup;
        packetCleanup = null;
        if (cleanup != null) {
            try { cleanup.run(); }
            catch (RuntimeException | LinkageError failure) {
                // Optional networking cleanup must not prevent final inventory snapshots and pool drainage.
                Log.w(failure, "Cannot completely detach craft packet provider");
            }
        }
    }

    private static boolean registerProvider(Plugin plugin, String provider) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled(provider)) return false;
        String className = "packetevents".equals(provider)
                ? "ru.endlesscode.rpginventory.compat.packetevents.PacketEventsCraftIntegration"
                : "ru.endlesscode.rpginventory.compat.protocol.ProtocolCraftIntegration";
        try {
            Class<?> integration = Class.forName(className, true, CraftManager.class.getClassLoader());
            Runnable cleanup = (Runnable) integration.getMethod("register", Plugin.class, Predicate.class)
                    .invoke(null, plugin, (Predicate<Player>) CraftManager::blocksRecipeBook);
            if (cleanup == null) return false;
            packetCleanup = cleanup;
            Log.i("Craft packet provider: {0}", provider);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            Log.w(failure, "Cannot enable craft packet provider " + provider);
            return false;
        }
    }

    /** Pending storage and locked craft extensions both forbid automatic transfers from the player's inventory. */
    private static boolean blocksRecipeBook(Player player) {
        if (!InventoryManager.isAllowedWorld(player.getWorld())) return false;
        if (!InventoryManager.playerIsLoaded(player)) return true;
        return (InventoryManager.get(player).isPocketCraft() || Config.getConfig().getBoolean("craft.workbench", true))
                && !getExtensions(player).isEmpty();
    }

    @NotNull
    public static List<CraftExtension> getExtensions(Player player) {
        List<CraftExtension> extensions = new ArrayList<>(EXTENSIONS);
        for (CraftExtension extension : EXTENSIONS) {
            if (extension.isUnlockedForPlayer(player)) {
                extension.registerExtension(extensions);
            }
        }

        return extensions;
    }

    public static Texture getTextureOfExtendable() {
        return textureOfExtendable;
    }

    @Nullable
    static CraftExtension getByName(String childName) {
        for (CraftExtension extension : EXTENSIONS) {
            if (extension.getName().equals(childName)) {
                return extension;
            }
        }

        return null;
    }
}
