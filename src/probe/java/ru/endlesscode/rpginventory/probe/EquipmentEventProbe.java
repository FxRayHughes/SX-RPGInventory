package ru.endlesscode.rpginventory.probe;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import ru.endlesscode.rpginventory.event.RPGInventoryEquipEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only observations of the public equipment event, limited to the explicit disposable test account prefix. */
public final class EquipmentEventProbe implements Listener {
    private static final Map<UUID, List<Map<String, Object>>> EVENTS = new HashMap<>();
    private static final Map<UUID, Long> SEQUENCES = new HashMap<>();
    private static boolean registered;

    /** Register before the first test command mutates anything; production reload does not remove probe-owned listeners. */
    public static void start() {
        if (registered) return;
        Bukkit.getPluginManager().registerEvents(new EquipmentEventProbe(), Bukkit.getPluginManager().getPlugin("SX-RPGInventory-Probe"));
        registered = true;
    }

    /** Record changed configured indices without touching the event's cloned items or the actual equipment. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void changed(RPGInventoryEquipEvent event) {
        if (!event.getPlayer().getName().matches("^SXRPGTest[A-Za-z0-9_]{0,7}$")) return;
        UUID player = event.getPlayer().getUniqueId();
        long sequence = SEQUENCES.getOrDefault(player, 0L) + 1;
        SEQUENCES.put(player, sequence);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sequence", sequence);
        record.put("changedSlots", new ArrayList<>(event.getChangedSlots()));
        List<Map<String, Object>> entries = EVENTS.computeIfAbsent(player, ignored -> new ArrayList<>());
        entries.add(record);
        // Keep a recent observation window while sequence numbers remain monotonic across reconnects.
        if (entries.size() > 64) entries.remove(0);
    }

    /** Snapshots are detached so test serialization cannot mutate the retained event ledger. */
    public static List<Map<String, Object>> snapshot(Player player) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> entries = EVENTS.get(player.getUniqueId());
        if (entries != null) {
            for (Map<String, Object> entry : entries) {
                Map<String, Object> copy = new LinkedHashMap<>(entry);
                copy.put("changedSlots", new ArrayList<>((List<?>) entry.get("changedSlots")));
                result.add(copy);
            }
        }
        return result;
    }
}
