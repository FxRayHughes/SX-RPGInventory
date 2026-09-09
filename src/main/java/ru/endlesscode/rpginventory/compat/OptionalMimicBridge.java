package ru.endlesscode.rpginventory.compat;

import org.bukkit.entity.Player;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.utils.Log;

import java.util.List;

/** Isolates every Mimic API reference behind a reflectively loaded implementation for SX-only servers. */
public final class OptionalMimicBridge {
    private static Adapter adapter;

    private OptionalMimicBridge() { }

    /** Only Bukkit/JDK types may appear here; the core JVM verifier must not need the optional plugin. */
    public interface Adapter {
        /** Providers are logged after dependency plugins have completed enable. */
        void enable();
        /** Ask the configured level provider instead of assuming vanilla experience levels. */
        boolean checkLevel(Player player, int required);
        /** Preserve the class provider's matching semantics for configured class restrictions. */
        boolean checkClass(Player player, List<String> classes);
        /** Check and debit the same provider; false leaves its level balance unchanged. */
        boolean takeLevels(Player player, int required);
    }

    /** Register Mimic inventory/item providers during onLoad only when its plugin has been discovered. */
    public static void load(RPGInventory plugin) {
        adapter = null;
        if (plugin.getServer().getPluginManager().getPlugin("Mimic") == null) return;
        try {
            Class<?> implementation = Class.forName(
                    "ru.endlesscode.rpginventory.compat.mimic.MimicIntegration", true,
                    OptionalMimicBridge.class.getClassLoader());
            adapter = (Adapter) implementation.getConstructor(RPGInventory.class).newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError failure) {
            Log.w(failure, "Mimic integration unavailable; using vanilla levels and class permissions.");
        }
    }

    /** A dependency that failed enable cannot safely provide inventory, level or class operations. */
    public static void enable(RPGInventory plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Mimic")) adapter = null;
        if (adapter != null) adapter.enable();
        else Log.i("Mimic unavailable; using vanilla levels and rpginventory.class.<name> permissions.");
    }

    /** Vanilla levels remain usable on servers with no active Mimic integration. */
    public static boolean checkLevel(Player player, int required) {
        return adapter == null ? player.getLevel() >= required : adapter.checkLevel(player, required);
    }

    /** Empty class requirements are unrestricted; nonempty fallback requirements need explicit permission. */
    public static boolean checkClass(Player player, List<String> classes) {
        if (adapter != null) return adapter.checkClass(player, classes);
        if (classes.isEmpty()) return true;
        for (String name : classes) {
            if (player.hasPermission("rpginventory.class." + name)) return true;
        }
        return false;
    }

    /** Debit only after the same provider confirms sufficient levels, including the no-Mimic fallback. */
    public static boolean takeLevels(Player player, int required) {
        if (adapter != null) return adapter.takeLevels(player, required);
        if (player.getLevel() < required) return false;
        player.setLevel(player.getLevel() - required);
        return true;
    }
}
