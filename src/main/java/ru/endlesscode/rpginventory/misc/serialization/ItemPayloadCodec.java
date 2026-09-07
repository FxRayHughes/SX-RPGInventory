package ru.endlesscode.rpginventory.misc.serialization;

import org.bukkit.inventory.ItemStack;
import ru.endlesscode.rpginventory.compat.ItemCompatibility;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Versioned native item payloads preserve complete components, PDC and third-party NBT (including SX-Item).
 * Encode/decode on the server thread; only the resulting text/bytes may cross into a storage worker.
 */
public final class ItemPayloadCodec {
    private ItemPayloadCodec() { }
    // A prefix prevents pre-component servers from treating newer component compounds as empty old items.
    private static final String MODERN = "native-v2:";
    private static final String LEGACY = "legacy-nbt-v2:";
    private static final String MODERN_V1 = "native-v1:";
    private static final String LEGACY_V1 = "legacy-nbt-v1:";

    /** Preserve empty positions rather than compacting items into another configured equipment slot. */
    public static List<String> encode(List<ItemStack> items) {
        List<String> encoded = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            encoded.add(ItemCompatibility.isEmpty(item) ? null
                    : (ItemCompatibility.usesModernSerialization() ? MODERN : LEGACY)
                    + Base64.getEncoder().encodeToString(ItemCompatibility.serialize(item)));
        }
        return encoded;
    }

    /** Old unprefixed Paper snapshots remain readable; invalid native data fails the complete load. */
    public static List<ItemStack> decode(List<?> encoded) {
        List<ItemStack> items = new ArrayList<>(encoded.size());
        for (Object value : encoded) {
            if (value == null) items.add(null);
            else if (value instanceof String) {
                String text = (String) value;
                boolean envelope = text.startsWith(MODERN) || text.startsWith(LEGACY);
                boolean modern = !text.startsWith(LEGACY) && !text.startsWith(LEGACY_V1);
                if (text.startsWith(MODERN)) text = text.substring(MODERN.length());
                else if (text.startsWith(LEGACY)) text = text.substring(LEGACY.length());
                else if (text.startsWith(MODERN_V1)) text = text.substring(MODERN_V1.length());
                else if (text.startsWith(LEGACY_V1)) text = text.substring(LEGACY_V1.length());
                else if (text.indexOf(':') >= 0) throw new IllegalArgumentException("Unsupported item payload format prefix");
                byte[] bytes = Base64.getDecoder().decode(text);
                items.add(envelope ? ItemCompatibility.deserializeEnvelope(bytes, modern)
                        : ItemCompatibility.deserialize(bytes, modern));
            }
            else throw new IllegalArgumentException("Invalid item payload type: " + value.getClass().getName());
        }
        return items;
    }
}
