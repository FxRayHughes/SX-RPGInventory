package ru.endlesscode.rpginventory.probe;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredListener;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.backpack.Backpack;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackHolder;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only event and wrapper evidence: failures remain observable without saving or repairing any inventory. */
public final class InventoryEventProbe implements Listener {
    private static final Map<UUID, List<Map<String, Object>>> EVENTS = new HashMap<>();
    private static final Set<UUID> REPORTED_LISTENERS = new HashSet<>();
    private static boolean registered;

    /** The probe keeps its own listener generation across production reloads. */
    public static void start() {
        if (registered) return;
        Bukkit.getPluginManager().registerEvents(new InventoryEventProbe(), Bukkit.getPluginManager().getPlugin("SX-RPGInventory-Probe"));
        registered = true;
    }

    /** LOWEST reflects earlier peers at that priority; MONITOR captures the final ordinary-listener result. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeClick(InventoryClickEvent event) { click(event, "LOWEST"); }

    /** Observe cancelled clicks too, since manual inventory handlers intentionally cancel vanilla transactions. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterClick(InventoryClickEvent event) { click(event, "MONITOR"); }

    private void click(InventoryClickEvent event, String priority) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!allowed(player)) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "click"); record.put("priority", priority);
        record.put("action", event.getAction().name()); record.put("click", event.getClick().name());
        record.put("rawSlot", event.getRawSlot()); record.put("cancelled", event.isCancelled());
        record.put("cursor", item(event.getCursor())); record.put("current", item(event.getCurrentItem()));
        append(player, record);
    }

    /** The event inventory is the closing container, even if the player's current view has already changed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (!allowed(player)) return;
        Map<String, Object> record = view(player, event.getInventory());
        record.put("event", "close");
        int count = 0;
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    && item.getItemMeta().getDisplayName().startsWith("SXRPG:")) count += item.getAmount();
        }
        record.put("fixtureCount", count);
        append(player, record);
    }

    /** Report wrapper identity separately from inventory equality, because Bukkit may return fresh wrapper objects. */
    public static Map<String, Object> snapshot(Player player) {
        Map<String, Object> result = view(player, InventoryViewCompatibility.top(player.getOpenInventory()));
        List<Map<String, Object>> events = EVENTS.get(player.getUniqueId());
        result.put("events", events == null ? new ArrayList<>() : new ArrayList<>(events));
        // Legacy chat transport truncates long plain-text JSON; report the static listener catalog only once.
        if (REPORTED_LISTENERS.add(player.getUniqueId())) {
            List<String> listeners = new ArrayList<>();
            for (RegisteredListener listener : InventoryClickEvent.getHandlerList().getRegisteredListeners()) {
                listeners.add(listener.getPlugin().getName() + ":" + listener.getPriority() + ":" + listener.getListener().getClass().getSimpleName());
            }
            result.put("clickListeners", listeners);
        }
        return result;
    }

    private static Map<String, Object> view(Player player, Inventory inventory) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object holder = inventory.getHolder();
        result.put("holder", holder == null ? "null" : holder.getClass().getSimpleName());
        if (holder instanceof BackpackHolder) {
            Inventory original = ((BackpackHolder) holder).getInventory();
            result.put("topSameReference", inventory == original);
            result.put("topEquals", inventory.equals(original));
        }
        Backpack backpack = InventoryManager.playerIsLoaded(player) ? InventoryManager.get(player).getBackpack() : null;
        result.put("wrapperBackpack", backpack == null ? "" : backpack.getUniqueId().toString());
        result.put("backpackActive", backpack != null && BackpackStorage.isActive(backpack));
        int listeners = 0;
        for (RegisteredListener listener : InventoryCloseEvent.getHandlerList().getRegisteredListeners()) {
            if (listener.getListener().getClass().getSimpleName().equals("BackpackListener")) listeners++;
        }
        result.put("backpackCloseListeners", listeners);
        return result;
    }

    private static String item(ItemStack item) {
        if (item == null) return "null";
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : "";
        // The response already carries run identity; repeating a full run name in every cursor/slot wastes chat budget.
        if (name.startsWith("SXRPG:")) name = "fixture:" + name.substring(name.lastIndexOf(':') + 1);
        if (name.length() > 24) name = name.substring(0, 24);
        return item.getType().name() + "x" + item.getAmount() + ":" + name;
    }

    private static boolean allowed(Player player) { return player.getName().matches("^SXRPGTest[A-Za-z0-9_]{0,7}$"); }

    private static void append(Player player, Map<String, Object> record) {
        List<Map<String, Object>> events = EVENTS.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
        events.add(record);
        // Bound chat response size as well as memory; recent clicks plus the last close suffice for each checkpoint.
        if (events.size() > 8) events.remove(0);
    }
}
