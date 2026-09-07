package ru.endlesscode.rpginventory.item;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import ru.endlesscode.rpginventory.compat.ItemCompatibility;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.misc.config.TexturesType;
import ru.endlesscode.rpginventory.utils.ItemUtils;
import ru.endlesscode.rpginventory.utils.Log;
import ru.endlesscode.rpginventory.compat.SXItemBridge;

import java.util.Objects;

public class Texture {

    private static final Texture EMPTY_TEXTURE = new Texture(new ItemStack(Material.AIR));

    @NotNull
    private final ItemStack prototype;
    private final int data;

    private Texture(@NotNull ItemStack prototype) {
        this(prototype, (short) -1);
    }

    private Texture(@NotNull ItemStack prototype, int data) {
        this.prototype = prototype;
        this.data = data;
    }

    public boolean isEmpty() {
        return this.equals(EMPTY_TEXTURE);
    }

    @NotNull
    public ItemStack getItemStack() {
        return prototype.clone();
    }

    public int getData() {
        return data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Texture)) {
            return false;
        }

        Texture texture = (Texture) o;
        return prototype.equals(texture.prototype);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prototype);
    }

    public static Texture parseTexture(String texture) {
        if (texture == null) {
            return EMPTY_TEXTURE;
        }

        // Configured holders/items can use the SX-Item generator without reconstructing foreign NBT or components.
        if (texture.regionMatches(true, 0, "sxitem:", 0, 7)) {
            return new Texture(SXItemBridge.generate(texture.substring(7), null));
        }

        String[] textureParts = texture.split(":");

        Material material = ItemCompatibility.material(textureParts[0]);
        if (material == null) {
            Log.w("Unknown material: {0}", textureParts[0]);
            return EMPTY_TEXTURE;
        }

        ItemStack item = ItemUtils.toBukkitItemStack(new ItemStack(material));
        if (ItemUtils.isEmpty(item)) {
            return EMPTY_TEXTURE;
        }

        // Keep modern default spawn-egg textures usable on 1.12, including the configured pet entity.
        if (material.name().equals("MONSTER_EGG") && textureParts[0].toUpperCase(java.util.Locale.ROOT).endsWith("_SPAWN_EGG")) {
            item = ItemCompatibility.spawnEgg(item, textureParts[0].substring(0, textureParts[0].length() - "_SPAWN_EGG".length()));
        }

        if (textureParts.length > 1) {
            // MONSTER_EGG before 1.13
            if (textureParts[0].equalsIgnoreCase("MONSTER_EGG")) {
                return parseLegacyMonsterEgg(item, textureParts[1]);
            } else if (material.name().startsWith("LEATHER_")) {
                return parseLeatherArmor(item, textureParts[1]);
            } else {
                return parseItemWithData(item, textureParts[1]);
            }
        }

        return new Texture(item);
    }

    private static Texture parseLegacyMonsterEgg(ItemStack item, String entityType) {
        // The helper uses EntityTag only on pre-flattening servers and explicit spawn-egg materials elsewhere.
        return new Texture(ItemCompatibility.spawnEgg(item, entityType));
    }

    private static Texture parseLeatherArmor(ItemStack item, String hexColor) {
        try {
            LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
            assert meta != null;
            meta.setColor(Color.fromRGB(Integer.parseInt(hexColor, 16)));
            item.setItemMeta(meta);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            Log.w("Can''t parse leather color: {0}", e.toString());
        }

        return new Texture(item);
    }

    private static Texture parseItemWithData(ItemStack item, String textureDataValue) {
        if (item.getItemMeta() == null) {
            return new Texture(item);
        }

        int textureData = -1;
        try {
            textureData = Integer.parseInt(textureDataValue);
        } catch (NumberFormatException e) {
            Log.w("Can''t parse texture modifier. Specify a number instead of \"{0}\"", textureData);
        }

        if (Config.texturesType == TexturesType.DAMAGE) {
            return parseItemWithDurability(item, textureData);
        }
        return parseItemWithCustomModelData(item, textureData);
    }

    private static Texture parseItemWithDurability(ItemStack item, int damage) {
        ItemMeta meta = item.getItemMeta();
        assert meta != null;

        meta.addItemFlags(ItemFlag.values());
        if (damage != -1) {
            if (ItemUtils.isItemHasDurability(item)) {
                meta.setUnbreakable(true);
            }
        }
        item.setItemMeta(meta);
        // Set damage after flags/unbreakable so a stale meta copy cannot erase the version-specific write.
        if (damage != -1) ItemCompatibility.setDamage(item, damage);

        return new Texture(item, damage);
    }

    private static Texture parseItemWithCustomModelData(ItemStack item, int customModelData) {
        ItemMeta meta = item.getItemMeta();
        assert meta != null;

        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        // Pre-1.14 clients use damage predicates, handled explicitly by the compatibility layer.
        if (customModelData != -1) ItemCompatibility.setCustomModelData(item, customModelData);

        return new Texture(item, customModelData);
    }
}
