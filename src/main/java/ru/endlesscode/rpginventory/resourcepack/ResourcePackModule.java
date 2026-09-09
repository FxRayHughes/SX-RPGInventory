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

package ru.endlesscode.rpginventory.resourcepack;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import java.nio.charset.StandardCharsets;
import ru.endlesscode.rpginventory.compat.ServerCompatibility;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
// Use Bukkit scheduling directly; the old wrapper implements an obsolete Plugin interface.
import org.bukkit.scheduler.BukkitRunnable;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.utils.EffectUtils;
import ru.endlesscode.rpginventory.utils.Log;
import ru.endlesscode.rpginventory.utils.PlayerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Created by OsipXD on 02.10.2015
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class ResourcePackModule implements Listener {

    private static final int TICKS_IN_SECOND = 20;

    private final Plugin plugin;
    private final String resourcePackUrl;
    private final String resourcePackHash;
    // A stable UUID distinguishes this pack from requests made by other plugins.
    private final UUID resourcePackId;

    private final List<UUID> loadList = new ArrayList<>();

    private ResourcePackModule(Plugin plugin, @NotNull String resourcePackUrl, String resourcePackHash) {
        this.plugin = plugin;
        this.resourcePackUrl = resourcePackUrl;
        this.resourcePackHash = resourcePackHash;
        this.resourcePackId = UUID.nameUUIDFromBytes((resourcePackUrl + resourcePackHash).getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    public static ResourcePackModule init(Plugin plugin) {
        final FileConfiguration config = Config.getConfig();
        if (!config.getBoolean("resource-pack.enabled", false)) {
            Log.i("Resource-pack is disabled in config");
            return null;
        }

        final String rpUrl = Config.getConfig().getString("resource-pack.url");
        final String rpHash = Config.getConfig().getString("resource-pack.hash");
        ResourcePackValidator validator = new ResourcePackValidator();
        boolean isLegalUrlAndHash = validator.validateUrlAndHash(rpUrl, rpHash);
        printErrorsIfNotEmpty(validator.getErrors());
        if (!isLegalUrlAndHash) {
            Log.s("Resource-pack can not be enabled");
            return null;
        }

        ResourcePackModule resourcePackModule = new ResourcePackModule(plugin, rpUrl, rpHash);
        plugin.getServer().getPluginManager().registerEvents(resourcePackModule, plugin);
        return resourcePackModule;
    }

    private static void printErrorsIfNotEmpty(@NotNull List<String> messages) {
        if (messages.isEmpty()) {
            return;
        }

        Log.w("");
        Log.w("######### Something wrong with RP settings! ##########");
        for (String message : messages) {
            Log.w("# {0}", message);
        }
        Log.w("######################################################");
        Log.w("");
    }

    public void loadResourcePack(@NotNull Player player, boolean skipJoinMessage) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1));

        if (skipJoinMessage) {
            this.sendResourcePack(player);
        } else if (InventoryManager.isNewPlayer(player)) {
            if (!EffectUtils.showJoinMessage(player, "rp-info", () -> this.sendResourcePack(player))) {
                this.sendResourcePack(player);
            }
        } else {
            EffectUtils.showDefaultJoinMessage(player);
            this.sendResourcePack(player);
        }
    }

    private void sendResourcePack(@NotNull final Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                loadList.add(player.getUniqueId());
                // Bukkit owns the modern resource-pack packet format and includes the configured hash.
                ServerCompatibility.resourcePack(player, resourcePackId, resourcePackUrl, decodeHash(resourcePackHash));
            }
        }.runTaskLater(this.plugin, TICKS_IN_SECOND);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onTargetWhenPlayerNotLoaded(@NotNull EntityTargetLivingEntityEvent event) {
        if (event.getTarget() == null || event.getTarget().getType() != EntityType.PLAYER) {
            return;
        }

        if (!InventoryManager.playerIsLoaded((Player) event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteractWhenNotLoaded(@NotNull PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (InventoryManager.isAllowedWorld(player.getWorld()) && !InventoryManager.playerIsLoaded(player)) {
            PlayerUtils.sendMessage(player, RPGInventory.getLanguage().getMessage("error.rp.denied"));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (InventoryManager.isAllowedWorld(player.getWorld()) && !InventoryManager.playerIsLoaded(player)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            this.removePlayerFromLoadList(event.getPlayer());
        }
    }

    private void removePlayerFromLoadList(@NotNull Player player) {
        loadList.remove(player.getUniqueId());
    }


    /** Process only this plugin's request, through Bukkit's synchronous resource-pack lifecycle event. */
    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        if (!ServerCompatibility.isResourcePack(event, resourcePackId) || !loadList.contains(player.getUniqueId())) return;
        // Use names because newer statuses are absent from legacy Bukkit enum definitions.
        String status = event.getStatus().name();
        if ("SUCCESSFULLY_LOADED".equals(status)) {
            loadList.remove(player.getUniqueId());
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            InventoryManager.loadPlayerInventory(player);
        } else if (java.util.Arrays.asList("DECLINED", "FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD", "DISCARDED").contains(status)) {
            loadList.remove(player.getUniqueId());
            player.kickPlayer(RPGInventory.getLanguage().getMessage("error.rp.denied"));
        } // ACCEPTED and DOWNLOADED remain pending until the client reports application.

    }
    /** Decode the validator-approved SHA-1 without requiring Java 17's HexFormat on old servers. */
    private static byte[] decodeHash(String hash) {
        byte[] bytes = new byte[hash.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
