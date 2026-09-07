package ru.endlesscode.rpginventory.storage;

import org.bukkit.entity.Player;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.PlayerWrapper;
import ru.endlesscode.rpginventory.misc.serialization.InventorySnapshot;
import ru.endlesscode.rpginventory.misc.serialization.Serialization;
import ru.endlesscode.rpginventory.utils.Log;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread player lifecycle adapter; a pending load cannot expose a writable default inventory. */
public final class PlayerStorage {
    private static final Map<UUID, UUID> pending = new HashMap<>();
    private static final Map<UUID, StorageSession> sessions = new HashMap<>();
    private PlayerStorage() { }

    /** Load asynchronously and use a generation token to discard completions for a disconnected/replaced player. */
    public static void load(Player player) {
        UUID id = player.getUniqueId();
        if (pending.containsKey(id) || sessions.containsKey(id)) return;
        UUID generation = UUID.randomUUID();
        pending.put(id, generation);
        StorageService service = PersistenceModule.service();
        service.acquire(new StorageKey(StorageKey.Kind.PLAYER, id)).whenComplete((session, failure) -> {
            if (failure != null) {
                PersistenceModule.main(() -> failLoad(player, generation, failure));
                return;
            }
            byte[] payload = session.initialPayload();
            if (payload == null) {
                Path legacy = RPGInventory.getInstance().getDataPath().resolve("inventories").resolve(id + ".inv");
                service.readLegacy(session, legacy).whenComplete((bytes, error) -> PersistenceModule.main(() -> {
                    if (error != null) {
                        service.release(session);
                        failLoad(player, generation, error);
                    } else restore(player, generation, session, bytes, true);
                }));
            } else {
                PersistenceModule.main(() -> restore(player, generation, session, payload, false));
            }
        });
    }

    private static void restore(Player player, UUID generation, StorageSession session, byte[] payload, boolean migrate) {
        StorageService service = PersistenceModule.service();
        if (!isCurrent(player, generation) || !session.isActive()) {
            service.release(session);
            pending.remove(player.getUniqueId(), generation);
            return;
        }
        try {
            PlayerWrapper wrapper = payload == null ? new PlayerWrapper(player)
                    : Serialization.decode(payload, InventorySnapshot.class).restore(player);
            if (migrate) {
                // Commit a migrated/new record before making it interactive; the original file remains untouched.
                byte[] snapshot = Serialization.encode(wrapper.createSnapshot());
                service.save(session, snapshot, false).whenComplete((ignored, failure) -> PersistenceModule.main(() -> {
                    if (failure != null) failLoad(player, generation, failure);
                    else install(player, generation, session, wrapper);
                }));
            } else install(player, generation, session, wrapper);
        } catch (Exception failure) {
            service.release(session);
            failLoad(player, generation, failure);
        }
    }

    private static void install(Player player, UUID generation, StorageSession session, PlayerWrapper wrapper) {
        if (!isCurrent(player, generation) || !session.isActive()) {
            PersistenceModule.service().release(session);
            pending.remove(player.getUniqueId(), generation);
            return;
        }
        pending.remove(player.getUniqueId());
        sessions.put(player.getUniqueId(), session);
        InventoryManager.installLoadedInventory(player, wrapper);
    }

    /** Return false if no snapshot could be captured; the caller must retain the live wrapper for diagnosis. */
    public static boolean save(Player player, PlayerWrapper wrapper, boolean release) {
        StorageSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        try {
            byte[] snapshot = Serialization.encode(wrapper.createSnapshot());
            PersistenceModule.service().save(session, snapshot, release).whenComplete((ignored, failure) -> {
                if (failure != null) Log.w(failure, "Player inventory save was not acknowledged");
            });
            if (release) sessions.remove(player.getUniqueId(), session);
            return true;
        } catch (Exception failure) {
            Log.w(failure, "Cannot create player inventory snapshot");
            PersistenceModule.service().serializationFailed(session, failure);
            return false;
        }
    }

    /** Prevent pending callbacks from installing a disconnected player's inventory after reconnect or world change. */
    public static void cancelLoad(Player player) { pending.remove(player.getUniqueId()); }

    /** Called at interaction time so a paused server cannot continue editing after its backend lease expired. */
    public static boolean isActive(UUID player) {
        StorageSession session = sessions.get(player);
        return session != null && session.isActive();
    }

    private static boolean isCurrent(Player player, UUID generation) {
        return player.isOnline() && generation.equals(pending.get(player.getUniqueId()))
                && InventoryManager.isAllowedWorld(player.getWorld());
    }

    private static void failLoad(Player player, UUID generation, Throwable failure) {
        Log.w(failure, "Player inventory load failed; existing data was preserved");
        if (generation.equals(pending.get(player.getUniqueId()))) {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) player.kickPlayer("Your RPG inventory is unavailable or in use on another server. Please reconnect later.");
        }
    }
}
