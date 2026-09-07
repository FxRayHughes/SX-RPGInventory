package ru.endlesscode.rpginventory.event.listener;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.inventory.InventoryAction;
import ru.endlesscode.rpginventory.inventory.ArmorType;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.slot.Slot;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import ru.endlesscode.rpginventory.compat.SXAttributeBridge;

/** Target-aware dispenser validation is loaded only on servers that expose the 1.13+ event. */
public final class DispenseArmorListener implements Listener {
    /** Successful dispenser equips have no player inventory click; publish after vanilla commits the armor. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDispenseEquip(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity() instanceof Player) {
            SXAttributeBridge.equipmentChanged((Player) event.getTargetEntity());
        }
    }

    /** Apply the same RPG armor restrictions to dispensers as to player clicks, without replacing occupied armor. */
    @EventHandler
    public void onDispenseEquip(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity().getType() == EntityType.PLAYER) {
            ArmorType type = ArmorType.matchType(event.getItem());
            Player player = (Player) event.getTargetEntity();

            if (this.hasInventoryArmorByType(type, player)) {
                return;
            }
            if (InventoryManager.playerIsLoaded(player)) {
                Slot armorSlot = SlotManager.instance().getSlot(type.name());
                event.setCancelled(armorSlot != null
                        && !InventoryManager.validateArmor(player, InventoryAction.PLACE_ONE, armorSlot, event.getItem())
                );
            }
        }
    }

    /** Occupied armor slots are not dispenser targets, so their current equipment remains authoritative. */
    private boolean hasInventoryArmorByType(ArmorType type, Player player) {
        switch (type) {
            case HELMET:
                return player.getInventory().getHelmet() != null;
            case CHESTPLATE:
                return player.getInventory().getChestplate() != null;
            case LEGGINGS:
                return player.getInventory().getLeggings() != null;
            case BOOTS:
                return player.getInventory().getBoots() != null;
            case UNKNOWN:
            default:
                return true; // Non-armor items do not have an equipment target to validate.
        }
    }
}
