package ru.endlesscode.rpginventory.compat.protocol;

import com.comphenix.protocol.ProtocolLibrary;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import ru.endlesscode.rpginventory.compat.RecipeBookCompatibility;
import ru.endlesscode.rpginventory.event.listener.CraftListener;

import java.util.function.Predicate;

/** Loaded reflectively only after ProtocolLib discovery; external packet interfaces stay outside core linkage. */
public final class ProtocolCraftIntegration {
    private ProtocolCraftIntegration() { }

    /** Guard autofill before the overlay; a successful registration returns its owned listener cleanup. */
    public static Runnable register(Plugin plugin, Predicate<Player> blocked) {
        CraftInteractionListener interactions = new CraftInteractionListener();
        Runnable recipeCleanup = null;
        try {
            recipeCleanup = RecipeBookCompatibility.register(plugin, blocked);
            if (recipeCleanup == null) return null;
            ProtocolLibrary.getProtocolManager().addPacketListener(new CraftListener(plugin));
            plugin.getServer().getPluginManager().registerEvents(interactions, plugin);
            final Runnable removeRecipe = recipeCleanup;
            return () -> {
                try { removeRecipe.run(); }
                finally {
                    ProtocolLibrary.getProtocolManager().removePacketListeners(plugin);
                    HandlerList.unregisterAll(interactions);
                }
            };
        } catch (RuntimeException | LinkageError failure) {
            // Paper's dynamically registered recipe listener must also be removed before trying PacketEvents.
            try { if (recipeCleanup != null) recipeCleanup.run(); }
            catch (RuntimeException | LinkageError cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            finally {
                ProtocolLibrary.getProtocolManager().removePacketListeners(plugin);
                HandlerList.unregisterAll(interactions);
            }
            throw failure;
        }
    }
}
