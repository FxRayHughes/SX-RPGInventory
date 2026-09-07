package ru.endlesscode.rpginventory.compat.mimic;

import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import ru.endlesscode.mimic.Mimic;
import ru.endlesscode.mimic.MimicApiLevel;
import ru.endlesscode.mimic.level.BukkitLevelSystem;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.compat.OptionalMimicBridge;
import ru.endlesscode.rpginventory.utils.Log;

import java.util.List;

/** Loaded only after Mimic discovery; all externally typed objects stay inside this integration package. */
public final class MimicIntegration implements OptionalMimicBridge.Adapter {
    private final Mimic mimic;

    /** Match the historical registration lifecycle and API levels without exposing provider classes to core. */
    public MimicIntegration(RPGInventory plugin) {
        if (!MimicApiLevel.checkApiLevel(MimicApiLevel.VERSION_0_8)) {
            throw new IllegalStateException("At least Mimic 0.8 is required for RPGInventory integration");
        }
        mimic = Mimic.getInstance();
        mimic.registerItemsRegistry(new RPGInventoryItemsRegistry(), MimicApiLevel.VERSION_0_7, plugin, ServicePriority.High);
        mimic.registerPlayerInventoryProvider(RPGInventoryPlayerInventory::new, MimicApiLevel.VERSION_0_8, plugin, ServicePriority.High);
    }

    /** Provider selection is complete after Mimic has enabled, rather than during registration in onLoad. */
    @Override
    public void enable() {
        Log.i("Level system ''{0}'' found.", mimic.getLevelSystemProvider().getId());
        Log.i("Class system ''{0}'' found.", mimic.getClassSystemProvider().getId());
    }

    /** Preserve configured provider semantics, which may differ from vanilla experience levels. */
    @Override
    public boolean checkLevel(Player player, int required) {
        return mimic.getLevelSystem(player).didReachLevel(required);
    }

    /** Delegate class aliases and inheritance to Mimic rather than recreating provider rules. */
    @Override
    public boolean checkClass(Player player, List<String> classes) {
        return mimic.getClassSystem(player).hasAnyOfClasses(classes);
    }

    /** Read and mutate the same player level-system instance to avoid inconsistent provider selection. */
    @Override
    public boolean takeLevels(Player player, int required) {
        BukkitLevelSystem levels = mimic.getLevelSystem(player);
        if (!levels.didReachLevel(required)) return false;
        levels.takeLevels(required);
        return true;
    }
}
