package ru.endlesscode.rpginventory.probe;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.compat.ItemCompatibility;
import ru.endlesscode.rpginventory.inventory.craft.CraftExtension;
import ru.endlesscode.rpginventory.inventory.craft.CraftManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Directed outbound-path coverage without changing a server inventory or bypassing production PacketEvents listeners. */
public final class PacketSlotProbe extends PacketListenerAbstract {
    private static final Map<UUID, Frame> FRAMES = new ConcurrentHashMap<>();
    private static boolean registered;

    private PacketSlotProbe() { super(PacketListenerPriority.MONITOR); }

    /** Track only an explicitly prepared disposable player; packet callbacks never access Bukkit inventory state. */
    public static void track(Player player) {
        if (!registered) {
            PacketEvents.getAPI().getEventManager().registerListener(new PacketSlotProbe());
            registered = true;
        }
        FRAMES.put(player.getUniqueId(), new Frame(-1, 0));
    }

    /** Release tracking alongside the probe-owned permission attachment. */
    public static void release(Player player) { FRAMES.remove(player.getUniqueId()); }

    /** Retain actual server window/state identifiers instead of inventing protocol state for the diagnostic packet. */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        UUID id = event.getUser().getUUID();
        Frame previous = id == null ? null : FRAMES.get(id);
        if (previous == null || event.isCancelled()) return;
        if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW) {
            FRAMES.put(id, new Frame(new WrapperPlayServerOpenWindow(event).getContainerId(), 0));
        } else if (event.getPacketType() == PacketType.Play.Server.CLOSE_WINDOW) {
            FRAMES.put(id, new Frame(-1, 0));
        } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            if (packet.getWindowId() == previous.window) FRAMES.put(id, new Frame(previous.window, packet.getStateId()));
        } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            if (packet.getWindowId() == previous.window) FRAMES.put(id, new Frame(previous.window, packet.getStateId()));
        }
    }

    /** Send one empty real locked cell through the normal listener pipeline; no server ItemStack is written. */
    public static int sendEmptyLockedSlot(Player player) {
        Inventory top = InventoryViewCompatibility.top(player.getOpenInventory());
        Frame frame = FRAMES.get(player.getUniqueId());
        if (top.getType() != InventoryType.WORKBENCH || frame == null || frame.window < 1)
            throw new IllegalStateException("No observed current workbench window");
        for (CraftExtension extension : CraftManager.getExtensions(player)) {
            for (int slot : extension.getSlots()) {
                if (slot < 1 || slot >= top.getSize() || !ItemCompatibility.isEmpty(top.getItem(slot))) continue;
                WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(frame.window, frame.state, slot,
                        SpigotConversionUtil.fromBukkitItemStack(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR)));
                // sendPacket (not sendPacketSilently) must exercise the production SET_SLOT mask and its counter.
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
                return slot;
            }
        }
        throw new IllegalStateException("No empty real locked crafting slot available");
    }

    private static final class Frame {
        private final int window;
        private final int state;
        private Frame(int window, int state) { this.window = window; this.state = state; }
    }
}
