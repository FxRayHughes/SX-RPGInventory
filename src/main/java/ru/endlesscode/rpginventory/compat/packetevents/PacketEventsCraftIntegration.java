package ru.endlesscode.rpginventory.compat.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.compat.protocol.CraftInteractionListener;
import ru.endlesscode.rpginventory.compat.protocol.CraftPacketState;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.craft.CraftExtension;
import ru.endlesscode.rpginventory.inventory.craft.CraftManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Level;

/** Optional PacketEvents adapter; Bukkit objects are converted only on the main thread, never in Netty callbacks. */
public final class PacketEventsCraftIntegration extends PacketListenerAbstract {
    private static volatile PacketEventsCraftIntegration active;
    private final Plugin plugin;
    private final Predicate<Player> blocked;
    private final Map<UUID, CraftPacketState<ItemStack>> states = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> windows = new ConcurrentHashMap<>();
    private final Map<org.bukkit.inventory.ItemStack, ItemStack> converted = new IdentityHashMap<>();
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();
    private final AtomicLong recipesBlocked = new AtomicLong();
    private final AtomicLong windowsMasked = new AtomicLong();
    private final AtomicLong slotsMasked = new AtomicLong();
    private final CraftInteractionListener interactions = new CraftInteractionListener();
    private final StateListener stateListener = new StateListener();
    private BukkitTask publisher;
    private volatile boolean closed;

    private PacketEventsCraftIntegration(Plugin plugin, Predicate<Player> blocked) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
        this.blocked = blocked;
    }

    /** The caller loads this class only after discovery, and owns the returned cleanup on reload/disable. */
    public static Runnable register(Plugin plugin, Predicate<Player> blocked) {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) {
            throw new IllegalStateException("PacketEvents is installed but its API is not initialized");
        }
        PacketEventsCraftIntegration integration = new PacketEventsCraftIntegration(plugin, blocked);
        try {
            PacketEvents.getAPI().getEventManager().registerListener(integration);
            plugin.getServer().getPluginManager().registerEvents(integration.interactions, plugin);
            plugin.getServer().getPluginManager().registerEvents(integration.stateListener, plugin);
            // Permissions and lease state can change without a click, so authorization snapshots expire and refresh each tick.
            integration.publisher = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) integration.refresh(player,
                        InventoryViewCompatibility.type(player.getOpenInventory()));
            }, 1L, 1L);
            for (Player player : Bukkit.getOnlinePlayers()) integration.refresh(player,
                    InventoryViewCompatibility.type(player.getOpenInventory()));
            active = integration;
            return integration::close;
        } catch (RuntimeException | LinkageError failure) {
            integration.close();
            throw failure;
        }
    }

    /** Counters provide actual packet-path evidence without inspecting or exposing a player's inventory payload. */
    public static Map<String, Long> diagnostics() {
        PacketEventsCraftIntegration value = active;
        if (value == null) return Collections.emptyMap();
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("recipesBlocked", value.recipesBlocked.get());
        result.put("windowsMasked", value.windowsMasked.get());
        result.put("slotsMasked", value.slotsMasked.get());
        return result;
    }

    private void refresh(Player player, InventoryType type) {
        if (closed) return;
        UUID id = player.getUniqueId();
        try {
            boolean loaded = InventoryManager.playerIsLoaded(player);
            boolean workbench = type == InventoryType.WORKBENCH && loaded
                    && !CraftInteractionListener.isExtensionsNotNeededHere(player);
            Map<Integer, ItemStack> caps = new LinkedHashMap<>();
            if (workbench) {
                for (CraftExtension extension : CraftManager.getExtensions(player)) {
                    ItemStack cap = converted.computeIfAbsent(extension.getCapItem(),
                            item -> SpigotConversionUtil.fromBukkitItemStack(item.clone()));
                    for (int slot : extension.getSlots()) caps.put(slot, cap);
                }
            }
            states.put(id, new CraftPacketState<>(blocked.test(player), workbench, System.nanoTime(), caps));
            warned.remove(id);
        } catch (RuntimeException | LinkageError failure) {
            states.remove(id);
            if (warned.add(id)) plugin.getLogger().log(Level.SEVERE,
                    "Cannot publish PacketEvents craft state; recipe autofill remains blocked for " + id, failure);
        }
    }

    /** Never replay cancelled recipe packets: missing or expired state fails closed before vanilla can autofill. */
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (closed || event.isCancelled()) return;
        UUID id = event.getUser().getUUID();
        if (id == null) return;
        if (event.getPacketType() == PacketType.Play.Client.CRAFT_RECIPE_REQUEST) {
            CraftPacketState<ItemStack> state = states.get(id);
            if (state == null || state.blocksRecipe(System.nanoTime())) {
                event.setCancelled(true);
                recipesBlocked.incrementAndGet();
            }
        } else if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            windows.remove(id, new WrapperPlayClientCloseWindow(event).getWindowId());
        }
    }

    /** Preserve container/state IDs and cursor contents; only copied locked-slot payloads are substituted. */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (closed || event.isCancelled()) return;
        UUID id = event.getUser().getUUID();
        if (id == null) return;
        if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW) {
            windows.put(id, new WrapperPlayServerOpenWindow(event).getContainerId());
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.CLOSE_WINDOW) {
            windows.remove(id, new WrapperPlayServerCloseWindow(event).getWindowId());
            return;
        }
        CraftPacketState<ItemStack> state = states.get(id);
        Integer window = windows.get(id);
        if (state == null || window == null) return;
        if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            Map<Integer, ItemStack> caps = state.capsFor(window, packet.getWindowId());
            if (caps.isEmpty()) return;
            List<ItemStack> items = new ArrayList<>(packet.getItems());
            for (Map.Entry<Integer, ItemStack> cap : caps.entrySet()) {
                if (cap.getKey() < items.size()) items.set(cap.getKey(), cap.getValue().copy());
            }
            packet.setItems(items);
            event.markForReEncode(true);
            windowsMasked.incrementAndGet();
        } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            ItemStack cap = state.capsFor(window, packet.getWindowId()).get(packet.getSlot());
            if (cap == null) return;
            packet.setItem(cap.copy());
            event.markForReEncode(true);
            slotsMasked.incrementAndGet();
        }
    }

    private void close() {
        closed = true;
        if (publisher != null) publisher.cancel();
        HandlerList.unregisterAll(interactions);
        HandlerList.unregisterAll(stateListener);
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        states.clear();
        windows.clear();
        converted.clear();
        warned.clear();
        if (active == this) active = null;
    }

    /** Publish the incoming window before Bukkit emits its initial contents, without using a stale current view. */
    private final class StateListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void opened(InventoryOpenEvent event) {
            if (event.getPlayer() instanceof Player) refresh((Player) event.getPlayer(), event.getInventory().getType());
        }

        /** A closed workbench must not mask a later chest or the player's inventory. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void closed(InventoryCloseEvent event) {
            if (event.getPlayer() instanceof Player) refresh((Player) event.getPlayer(), InventoryType.CRAFTING);
        }

        /** Pending player storage is published as blocked before the client can send recipe placement. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void joined(PlayerJoinEvent event) { refresh(event.getPlayer(), InventoryType.CRAFTING); }

        /** Session UUID entries must not accumulate across reconnects. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void quit(PlayerQuitEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            states.remove(id);
            windows.remove(id);
            warned.remove(id);
        }
    }
}
