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

package ru.endlesscode.rpginventory.utils;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
// Use Bukkit scheduling directly; the old wrapper implements an obsolete Plugin interface.
import org.bukkit.scheduler.BukkitRunnable;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.compat.SoundCompat;
import ru.endlesscode.rpginventory.misc.config.Config;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * Created by OsipXD on 21.09.2015
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class EffectUtils {

    public static void playParticlesToAll(Particle particle, int particleNum, @NotNull Location location) {
        playParticlesToAll(particle, particleNum, location, 30.0D);
    }

    private static void playParticlesToAll(Particle particle, int particleNum, @NotNull Location location, double distance) {
        playParticlesToAll(particle, particleNum, location, LocationUtils.getRandomVector(), distance);
    }

    private static void playParticlesToAll(Particle particle, int particleNum, @NotNull Location location, @NotNull Vector direction, double distance) {
        for (Player player : LocationUtils.getNearbyPlayers(location, distance)) {
            playParticles(player, particle, particleNum, location, direction);
        }
    }

    private static void playParticles(Player player, Particle particle, int particleNum, Location location, Vector direction) {
        player.spawnParticle(particle, location, particleNum, direction.getX(), direction.getY(), direction.getZ());
    }

    public static void playSpawnEffect(Entity entity) {
        Location loc = entity.getLocation();

        entity.getWorld().playSound(loc, SoundCompat.ENDERMAN_TELEPORT.get(), 1, (float) (1.2 + Math.random() * 0.4));
        playParticlesToAll(ru.endlesscode.rpginventory.compat.ServerCompatibility.namedConstant(Particle.class, "EXPLOSION", "EXPLOSION_NORMAL"), 3, loc);
    }

    public static void playDespawnEffect(Entity entity) {
        Location loc = entity.getLocation();

        entity.getWorld().playSound(loc, SoundCompat.ENDERMAN_TELEPORT.get(), 1, (float) (0.6 + Math.random() * 0.4));
        playParticlesToAll(ru.endlesscode.rpginventory.compat.ServerCompatibility.namedConstant(Particle.class, "SMOKE", "SMOKE_NORMAL"), 3, loc);
    }


    public static void showDefaultJoinMessage(Player player) {
        showJoinMessage(player, "default", null);
    }

    public static boolean showJoinMessage(Player player, String messageId, @Nullable Runnable callback) {
        String configPrefix = "join-messages." + messageId;
        if (Config.getConfig().getBoolean(configPrefix + ".enabled", false)) {
            EffectUtils.sendTitle(player,
                    Config.getConfig().getInt("join-messages.delay", 2),
                    Config.getConfig().getString(configPrefix + ".title"),
                    Config.getConfig().getStringList(configPrefix + ".text"),
                    callback);
            return true;
        } else {
            return false;
        }
    }

    /** Bukkit owns title packet changes across protocol releases; preserve the configured subtitle timing. */
    private static void sendTitle(final Player player, int delay, String title, @NotNull final List<String> subtitles, @Nullable final Runnable callback) {
        int interval = Math.max(2, delay);
        String heading = StringUtils.coloredLine(StringUtils.setPlaceholders(player, title));
        player.resetTitle();
        player.sendTitle(heading, "", 10, interval * 20, 10);
        new BukkitRunnable() {
            int line;
            @Override public void run() {
                if (!player.isOnline() || line == subtitles.size()) {
                    cancel();
                    if (player.isOnline() && callback != null) callback.run();
                    return;
                }
                String subtitle = StringUtils.coloredLine(StringUtils.setPlaceholders(player, subtitles.get(line++)));
                player.sendTitle(heading, subtitle, 0, interval * 20, 10);
            }
        }.runTaskTimer(RPGInventory.getInstance(), 0, 20L * interval);
    }
}
