package ru.endlesscode.rpginventory.compat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Predicate;

/** Recipe autofill must pass the same craft-slot locks on Paper and on servers without Paper events. */
public final class RecipeBookCompatibility {
    private RecipeBookCompatibility() { }

    /** Return owned cleanup, or null if neither cancellation API exists; failed providers must leave no handlers. */
    public static Runnable register(Plugin plugin, Predicate<Player> blocked) {
        try {
            Class<? extends Event> eventType = Class.forName(
                    "com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent").asSubclass(Event.class);
            if (Cancellable.class.isAssignableFrom(eventType)) {
                final Method player = eventType.getMethod("getPlayer");
                final Listener owner = new Listener() { };
                try {
                plugin.getServer().getPluginManager().registerEvent(eventType, owner,
                        EventPriority.HIGHEST, (listener, event) -> {
                            try {
                                if (blocked.test((Player) player.invoke(event))) ((Cancellable) event).setCancelled(true);
                            } catch (ReflectiveOperationException failure) {
                                // A broken bridge must not silently permit autofill into locked slots.
                                ((Cancellable) event).setCancelled(true);
                                throw new org.bukkit.event.EventException(failure);
                            } catch (RuntimeException failure) {
                                ((Cancellable) event).setCancelled(true);
                                throw failure;
                            }
                        }, plugin, true);
                } catch (RuntimeException | LinkageError failure) {
                    HandlerList.unregisterAll(owner);
                    throw failure;
                }
                return () -> HandlerList.unregisterAll(owner);
            }
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            // Spigot has no Paper event; protect the incoming recipe-placement packet instead.
        }
        PacketAdapter registered = null;
        try {
            PacketType autofill = (PacketType) PacketType.Play.Client.class.getField("AUTO_RECIPE").get(null);
            if (!autofill.isSupported()) {
                plugin.getLogger().severe("Craft locks cannot start: ProtocolLib has no supported AUTO_RECIPE packet.");
                return null;
            }
            PacketAdapter adapter = new PacketAdapter(plugin, ListenerPriority.HIGHEST, autofill) {
                /** Guard recipe placement independently of normal inventory-click events. */
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    // Fail closed if an unusual ProtocolLib/server combination delivers this packet off-thread.
                    if (event.isAsync()) {
                        event.setCancelled(true);
                        return;
                    }
                    try {
                        if (blocked.test(event.getPlayer())) event.setCancelled(true);
                    } catch (RuntimeException failure) {
                        event.setCancelled(true);
                        throw failure;
                    }
                }
            };
            registered = adapter;
            ProtocolLibrary.getProtocolManager().addPacketListener(adapter);
            plugin.getLogger().info("Craft recipe-book protection uses ProtocolLib AUTO_RECIPE on this server.");
            return () -> ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (registered != null) ProtocolLibrary.getProtocolManager().removePacketListener(registered);
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Craft locks cannot start: no cancellable recipe-book API is available.", failure);
            return null;
        }
    }
}
