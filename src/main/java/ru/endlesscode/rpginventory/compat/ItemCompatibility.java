package ru.endlesscode.rpginventory.compat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Keeps optional modern Bukkit types out of the Java 8 core's linkage graph.
 * All operations run on the server thread; serialization always retains complete native item data.
 */
public final class ItemCompatibility {
    private static final String NAMESPACE = "sx-rpginventory";
    // SXRI + an explicit envelope version cannot collide with the gzip header of earlier native payloads.
    private static final int ITEM_MAGIC = 0x53585249;
    private static final int ITEM_ENVELOPE_VERSION = 2;
    private static final int MAX_NATIVE_BYTES = 64 * 1024 * 1024;
    private static LegacyNbt legacy;
    private static ComponentNbt components;

    private ItemCompatibility() { }

    /** Material#isAir did not exist on 1.12; names also cover the later cave/void variants. */
    public static boolean isEmpty(ItemStack item) {
        if (item == null) return true;
        String name = item.getType().name();
        return name.equals("AIR") || name.equals("CAVE_AIR") || name.equals("VOID_AIR");
    }

    /** Resolve renamed materials without linking fields that did not exist before the flattening. */
    public static Material material(String name) {
        Material direct = Material.matchMaterial(name);
        if (direct != null) return direct;
        if (name.toUpperCase(Locale.ROOT).endsWith("_SPAWN_EGG")) return Material.matchMaterial("MONSTER_EGG");
        if (name.equalsIgnoreCase("MONSTER_EGG")) return Material.matchMaterial("PIG_SPAWN_EGG");
        String[][] aliases = {
                {"GOLDEN_HORSE_ARMOR", "GOLD_BARDING"}, {"IRON_HORSE_ARMOR", "IRON_BARDING"},
                {"DIAMOND_HORSE_ARMOR", "DIAMOND_BARDING"}, {"PLAYER_HEAD", "SKULL_ITEM"},
                {"LEAD", "LEASH"}, {"OAK_FENCE", "FENCE"}, {"OAK_PLANKS", "WOOD"},
                {"GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE"}, {"GUNPOWDER", "SULPHUR"}
        };
        for (String[] pair : aliases) {
            if (pair[0].equalsIgnoreCase(name)) return Material.matchMaterial(pair[1]);
            if (pair[1].equalsIgnoreCase(name)) return Material.matchMaterial(pair[0]);
        }
        return null;
    }

    /** Preserve the literal legacy key on old servers and the established namespace on modern servers. */
    public static void setString(ItemStack item, String key, String value) { writeTag(item, key, "STRING", value); }

    /** Read both generations of persistent keys without using editable lore as identity. */
    public static String getString(ItemStack item, String key, String fallback) {
        Object value = readTag(item, key, "STRING");
        return value instanceof String ? (String) value : fallback;
    }

    /** Store timestamps as integers so long-running cooldowns never lose precision. */
    public static void setLong(ItemStack item, String key, long value) { writeTag(item, key, "LONG", value); }

    /** Missing timestamps retain the caller's semantic default. */
    public static long getLong(ItemStack item, String key, long fallback) {
        Object value = readTag(item, key, "LONG");
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    /** Fractional pet health uses the same storage type before and after the PDC transition. */
    public static void setDouble(ItemStack item, String key, double value) { writeTag(item, key, "DOUBLE", value); }

    /** Missing health is distinct from zero, which represents an actual exhausted pet. */
    public static double getDouble(ItemStack item, String key, double fallback) {
        Object value = readTag(item, key, "DOUBLE");
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static Object readTag(ItemStack item, String key, String type) {
        if (isEmpty(item)) return null;
        try {
            Method containerMethod = modernContainerMethod();
            if (containerMethod != null) {
                Object value = pdc(containerMethod.invoke(item.getItemMeta()), key, type, null, false);
                if (value != null) return value;
                // PDC arrived before the component codec: read old literal NBT even without SX-Item installed.
                if (VersionHandler.getVersionCode() < VersionHandler.VERSION_1_17) return legacy().readTag(item, key, type);
                // SX-Item resolves old unnamespaced tags in the modern custom_data component.
                return SXItemBridge.legacyTag(item, key);
            }
            return legacy().readTag(item, key, type);
        } catch (ReflectiveOperationException e) { throw failure("read persistent item key " + key, e); }
    }

    private static void writeTag(ItemStack item, String key, String type, Object value) {
        if (isEmpty(item)) return;
        try {
            Method containerMethod = modernContainerMethod();
            if (containerMethod != null) {
                ItemMeta meta = item.getItemMeta();
                pdc(containerMethod.invoke(meta), key, type, value, true);
                item.setItemMeta(meta);
            } else legacy().writeTag(item, key, type, value);
        } catch (ReflectiveOperationException e) { throw failure("write persistent item key " + key, e); }
    }

    private static Method modernContainerMethod() {
        try { return ItemMeta.class.getMethod("getPersistentDataContainer"); }
        catch (NoSuchMethodException unavailable) { return null; }
    }

    private static Object pdc(Object container, String key, String type, Object value, boolean write)
            throws ReflectiveOperationException {
        Class<?> keys = Class.forName("org.bukkit.NamespacedKey");
        Class<?> types = Class.forName("org.bukkit.persistence.PersistentDataType");
        Class<?> containers = Class.forName("org.bukkit.persistence.PersistentDataContainer");
        Object namespacedKey = keys.getConstructor(String.class, String.class)
                .newInstance(NAMESPACE, key.toLowerCase(Locale.ROOT));
        Object dataType = types.getField(type).get(null);
        if (write) return containers.getMethod("set", keys, types, Object.class).invoke(container, namespacedKey, dataType, value);
        return containers.getMethod("get", keys, types).invoke(container, namespacedKey, dataType);
    }

    /**
     * Native bytes retain every component/custom tag. A sidecar also retains Bukkit ItemFlags that Paper 1.20.6
     * can drop during native decoding; changing native attribute_modifiers would alter the item's combat stats.
     */
    public static byte[] serialize(ItemStack item) {
        try {
            Method modern = modernSerializer();
            byte[] nativeBytes = modern != null ? (byte[]) modern.invoke(item)
                    : usesComponentCodec() ? components().serialize(item) : legacy().serialize(item);
            if (nativeBytes.length == 0 || nativeBytes.length > MAX_NATIVE_BYTES) throw new IllegalArgumentException("Invalid native item payload length");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(ITEM_MAGIC);
                output.writeByte(ITEM_ENVELOPE_VERSION);
                output.writeBoolean(usesModernSerialization());
                output.writeInt(nativeBytes.length);
                output.write(nativeBytes);
                java.util.Set<ItemFlag> flags = item.getItemMeta().getItemFlags();
                output.writeShort(flags.size());
                for (ItemFlag flag : flags) output.writeUTF(flag.name());
            }
            return bytes.toByteArray();
        } catch (ReflectiveOperationException e) { throw failure("serialize complete item", e); }
        catch (IOException e) { throw new IllegalStateException("Cannot encode item envelope", e); }
    }

    /** Whether the persisted format is the server's data-fixer-aware byte format. */
    public static boolean usesModernSerialization() { return modernSerializer() != null || usesComponentCodec(); }

    /**
     * Arclight 1.20.1 exposes Mojang's component/NBT classes but omits the old
     * CraftBukkit NMS package (v1_20_R1). Select the component codec from the
     * class actually provided by the server instead of relying on a patch-level
     * version threshold, otherwise persistence falls into the missing legacy
     * NBTTagCompound reflection path.
     */
    private static boolean usesComponentCodec() {
        if (modernSerializer() != null) return false;
        try {
            Class.forName("net.minecraft.nbt.CompoundTag");
            Class.forName("net.minecraft.world.item.ItemStack");
            return true;
        } catch (ClassNotFoundException unavailable) {
            return false;
        }
    }

    private static Method modernSerializer() {
        try { return ItemStack.class.getMethod("serializeAsBytes"); }
        catch (NoSuchMethodException unavailable) { return null; }
    }

    /**
     * Versioned envelopes validate lengths before allocation. Earlier raw native bytes remain readable;
     * unknown versions and unsupported flag names fail the whole snapshot instead of silently losing metadata.
     */
    public static ItemStack deserialize(byte[] bytes, boolean modernFormat) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty item payload");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (bytes.length < 4 || input.readInt() != ITEM_MAGIC) return deserializeNative(bytes, modernFormat);
            int version = input.readUnsignedByte();
            if (version != ITEM_ENVELOPE_VERSION) throw new IllegalArgumentException("Unsupported item envelope version: " + version);
            boolean nativeModern = input.readBoolean();
            int length = input.readInt();
            if (length <= 0 || length > MAX_NATIVE_BYTES || length > input.available() - 2) {
                throw new IllegalArgumentException("Invalid native item payload length: " + length);
            }
            byte[] nativeBytes = new byte[length];
            input.readFully(nativeBytes);
            int count = input.readUnsignedShort();
            if (count > 256) throw new IllegalArgumentException("Invalid item flag count: " + count);
            ItemFlag[] flags = new ItemFlag[count];
            for (int i = 0; i < count; i++) flags[i] = savedFlag(input.readUTF());
            if (input.available() != 0) throw new IllegalArgumentException("Trailing bytes in item envelope");
            ItemStack item = deserializeNative(nativeBytes, nativeModern);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) throw new IllegalArgumentException("Native item has no metadata for its flag sidecar");
            // Leave every decoded native component intact and restore only missing Bukkit-visible flags.
            boolean missing = false;
            for (ItemFlag flag : flags) if (!meta.hasItemFlag(flag)) missing = true;
            if (missing) {
                meta.addItemFlags(flags);
                // CraftItemStack immediately projects meta back into native components. On 1.20.6 that same
                // projection drops HIDE_ATTRIBUTES again; Bukkit's full copy retains its complete CraftMeta
                // (including unhandled component/custom-data fields) and the restored API-only flag together.
                ItemStack restored = new ItemStack(item);
                restored.setItemMeta(meta);
                item = restored;
            }
            return item;
        } catch (IOException e) { throw new IllegalArgumentException("Truncated or invalid item envelope", e); }
    }

    /** A v2 text prefix promises an envelope; reject mismatched or missing headers instead of guessing. */
    public static ItemStack deserializeEnvelope(byte[] bytes, boolean modernFormat) {
        if (bytes == null || bytes.length < 6) throw new IllegalArgumentException("Missing item envelope header");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != ITEM_MAGIC) throw new IllegalArgumentException("Invalid item envelope magic");
            if (input.readUnsignedByte() != ITEM_ENVELOPE_VERSION) throw new IllegalArgumentException("Unsupported item envelope version");
            int kind = input.readUnsignedByte();
            if (kind != (modernFormat ? 1 : 0)) throw new IllegalArgumentException("Item envelope codec differs from its format prefix");
        } catch (IOException e) { throw new IllegalArgumentException("Truncated item envelope header", e); }
        return deserialize(bytes, modernFormat);
    }

    private static ItemFlag savedFlag(String name) {
        try { return ItemFlag.valueOf(name); }
        catch (IllegalArgumentException unavailable) {
            // Bukkit renamed this flag while retaining its hide-additional-tooltip meaning.
            if (name.equals("HIDE_POTION_EFFECTS")) {
                try { return ItemFlag.valueOf("HIDE_ADDITIONAL_TOOLTIP"); }
                catch (IllegalArgumentException ignored) { /* Keep the original unsupported-name diagnostic. */ }
            }
            throw new IllegalArgumentException("This server does not support persisted ItemFlag " + name, unavailable);
        }
    }

    /** A newer native item must never be silently down-converted by an older server. */
    private static ItemStack deserializeNative(byte[] bytes, boolean modernFormat) {
        try {
            Method modern = modernSerializer();
            if (modern != null) return (ItemStack) ItemStack.class.getMethod("deserializeBytes", byte[].class).invoke(null, (Object) bytes);
            if (usesComponentCodec()) return components().deserialize(bytes);
            if (modernFormat) throw new IllegalArgumentException("This server cannot read modern item payloads; use separate storage namespaces for different Minecraft generations");
            return legacy().deserialize(bytes);
        } catch (ReflectiveOperationException e) { throw failure("deserialize complete item", e); }
    }

    /** Damageable is absent on 1.12, whose durability field also encodes legacy material data. */
    public static int getDamage(ItemStack item) {
        try {
            Class<?> damageable = Class.forName("org.bukkit.inventory.meta.Damageable");
            ItemMeta meta = item.getItemMeta();
            return damageable.isInstance(meta) ? ((Number) damageable.getMethod("getDamage").invoke(meta)).intValue() : 0;
        } catch (ClassNotFoundException oldServer) { return item.getDurability(); }
        catch (ReflectiveOperationException e) { throw failure("read damage", e); }
    }

    /** Commit modern meta only after mutation; legacy durability is written directly to the stack. */
    public static void setDamage(ItemStack item, int value) {
        try {
            Class<?> damageable = Class.forName("org.bukkit.inventory.meta.Damageable");
            ItemMeta meta = item.getItemMeta();
            if (damageable.isInstance(meta)) {
                damageable.getMethod("setDamage", int.class).invoke(meta, value);
                item.setItemMeta(meta);
            }
        } catch (ClassNotFoundException oldServer) { item.setDurability((short) value); }
        catch (ReflectiveOperationException e) { throw failure("write damage", e); }
    }

    /** Old clients use damage predicates for textures because CustomModelData was introduced in 1.14. */
    public static int getCustomModelData(ItemStack item) {
        try {
            Method has = ItemMeta.class.getMethod("hasCustomModelData");
            ItemMeta meta = item.getItemMeta();
            return Boolean.TRUE.equals(has.invoke(meta)) ? ((Number) ItemMeta.class.getMethod("getCustomModelData").invoke(meta)).intValue() : 0;
        } catch (NoSuchMethodException oldServer) { return getDamage(item); }
        catch (ReflectiveOperationException e) { throw failure("read model data", e); }
    }

    /** Use the version's texture mechanism without resolving newer ItemMeta methods on old Bukkit. */
    public static void setCustomModelData(ItemStack item, int value) {
        try {
            Method setter = ItemMeta.class.getMethod("setCustomModelData", Integer.class);
            ItemMeta meta = item.getItemMeta();
            setter.invoke(meta, Integer.valueOf(value));
            item.setItemMeta(meta);
        } catch (NoSuchMethodException oldServer) { setDamage(item, value); }
        catch (ReflectiveOperationException e) { throw failure("write model data", e); }
    }

    /** 1.12 spawn eggs encode the entity in EntityTag; later versions use separate material identifiers. */
    public static ItemStack spawnEgg(ItemStack legacyEgg, String entity) {
        Material material = Material.matchMaterial(entity.toUpperCase(Locale.ROOT) + "_SPAWN_EGG");
        if (material != null) return new ItemStack(material);
        try { legacy().spawnEgg(legacyEgg, entity); return legacyEgg; }
        catch (ReflectiveOperationException e) { throw failure("configure spawn egg " + entity, e); }
    }

    private static LegacyNbt legacy() {
        if (legacy == null) {
            try { legacy = new LegacyNbt(); }
            catch (ReflectiveOperationException e) { throw failure("resolve legacy native item codec", e); }
        }
        return legacy;
    }

    private static ComponentNbt components() {
        if (components == null) {
            try { components = new ComponentNbt(); }
            catch (ReflectiveOperationException e) { throw failure("resolve component item codec", e); }
        }
        return components;
    }

    private static IllegalStateException failure(String operation, ReflectiveOperationException cause) {
        Throwable actual = cause instanceof java.lang.reflect.InvocationTargetException ? cause.getCause() : cause;
        return new IllegalStateException("Cannot " + operation + "; item data has not been replaced", actual);
    }

    /**
     * Spigot has no Paper item-byte API. Its Mojang codec retains every component, including unknown-to-Bukkit
     * custom data, and its DataFixer upgrades complete older compounds before decoding.
     */
    private static final class ComponentNbt {
        private final Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
        private final Class<?> nmsItem = Class.forName("net.minecraft.world.item.ItemStack");
        private final Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
        private final Class<?> dynamic = Class.forName("com.mojang.serialization.Dynamic");
        private final Class<?> dataResult = Class.forName("com.mojang.serialization.DataResult");
        private final Class<?> craftItem;
        private final Object codec;
        private final Object ops;
        private final Object nbtOps;
        private final Object fixer;
        private final Object itemReference;
        private final int dataVersion;

        ComponentNbt() throws ReflectiveOperationException {
            craftItem = Class.forName(Bukkit.getServer().getClass().getPackage().getName() + ".inventory.CraftItemStack");
            codec = nmsItem.getField("CODEC").get(null);
            Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object registries = Class.forName("net.minecraft.server.MinecraftServer").getMethod("registryAccess").invoke(server);
            nbtOps = Class.forName("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
            ops = Class.forName("net.minecraft.core.HolderLookup$Provider").getMethod("createSerializationContext", dynamicOps)
                    .invoke(registries, nbtOps);
            fixer = Class.forName("net.minecraft.server.MinecraftServer").getMethod("getFixerUpper").invoke(server);
            itemReference = Class.forName("net.minecraft.util.datafix.fixes.References").getField("ITEM_STACK").get(null);
            dataVersion = ((Number) Class.forName("org.bukkit.UnsafeValues").getMethod("getDataVersion").invoke(Bukkit.getUnsafe())).intValue();
        }

        byte[] serialize(ItemStack item) throws ReflectiveOperationException {
            Object nativeItem = craftItem.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            Object result = Class.forName("com.mojang.serialization.Encoder").getMethod("encodeStart", dynamicOps, Object.class)
                    .invoke(codec, ops, nativeItem);
            Object data = dataResult.getMethod("getOrThrow").invoke(result);
            compound.getMethod("putInt", String.class, int.class).invoke(data, "DataVersion", dataVersion);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Class.forName("net.minecraft.nbt.NbtIo").getMethod("writeCompressed", compound, OutputStream.class).invoke(null, data, output);
            return output.toByteArray();
        }

        ItemStack deserialize(byte[] bytes) throws ReflectiveOperationException {
            Class<?> accounter = Class.forName("net.minecraft.nbt.NbtAccounter");
            // The payload is a trusted local snapshot, but bounding native allocation still rejects corrupt input.
            Object budget = accounter.getMethod("create", long.class).invoke(null, 64L * 1024L * 1024L);
            Object data = Class.forName("net.minecraft.nbt.NbtIo").getMethod("readCompressed", InputStream.class, accounter)
                    .invoke(null, new ByteArrayInputStream(bytes), budget);
            Object storedVersion = compound.getMethod("getInt", String.class).invoke(data, "DataVersion");
            if (storedVersion instanceof java.util.Optional) storedVersion = ((java.util.Optional<?>) storedVersion).orElse(null);
            if (!(storedVersion instanceof Number)) throw new IllegalArgumentException("Item payload is missing its DataVersion");
            int previousVersion = ((Number) storedVersion).intValue();
            if (previousVersion > dataVersion) throw new IllegalArgumentException("Cannot downgrade item data from " + previousVersion + " to " + dataVersion);
            if (previousVersion < dataVersion) {
                Object input = dynamic.getConstructor(dynamicOps, Object.class).newInstance(nbtOps, data);
                Object fixed = Class.forName("com.mojang.datafixers.DataFixer")
                        .getMethod("update", Class.forName("com.mojang.datafixers.DSL$TypeReference"), dynamic, int.class, int.class)
                        .invoke(fixer, itemReference, input, previousVersion, dataVersion);
                data = dynamic.getMethod("getValue").invoke(fixed);
            }
            Object result = Class.forName("com.mojang.serialization.Decoder").getMethod("parse", dynamicOps, Object.class)
                    .invoke(codec, ops, data);
            Object nativeItem = dataResult.getMethod("getOrThrow").invoke(result);
            return (ItemStack) craftItem.getMethod("asBukkitCopy", nmsItem).invoke(null, nativeItem);
        }
    }

    /** Full compound codec for the pre-component versioned CraftBukkit implementations (1.12 through 1.16). */
    private static final class LegacyNbt {
        private final Class<?> compound;
        private final Class<?> nmsItem;
        private final Class<?> craftItem;
        private final Method writeCompressed;
        private final Method readCompressed;
        private final Method save;

        LegacyNbt() throws ReflectiveOperationException {
            String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
            String revision = craftPackage.substring(craftPackage.lastIndexOf('.') + 1);
            String nmsPackage = "net.minecraft.server." + revision + ".";
            craftItem = Class.forName(craftPackage + ".inventory.CraftItemStack");
            compound = Class.forName(nmsPackage + "NBTTagCompound");
            nmsItem = Class.forName(nmsPackage + "ItemStack");
            Class<?> io = Class.forName(nmsPackage + "NBTCompressedStreamTools");
            writeCompressed = staticMethod(io, void.class, compound, OutputStream.class);
            readCompressed = staticMethod(io, compound, InputStream.class);
            save = nmsItem.getMethod("save", compound);
        }

        private Object nativeItem(ItemStack item) throws ReflectiveOperationException {
            return craftItem.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
        }

        private ItemStack bukkitItem(Object item) throws ReflectiveOperationException {
            return (ItemStack) craftItem.getMethod("asBukkitCopy", nmsItem).invoke(null, item);
        }

        byte[] serialize(ItemStack item) throws ReflectiveOperationException {
            Object data = compound.getConstructor().newInstance();
            save.invoke(nativeItem(item), data);
            // Paper's native decoder requires a DataVersion when upgrading old full item compounds.
            int version = VersionHandler.getVersionCode() < VersionHandler.VERSION_1_13 ? 1343 : 2586;
            try { version = ((Number) Bukkit.getUnsafe().getClass().getMethod("getDataVersion").invoke(Bukkit.getUnsafe())).intValue(); }
            catch (NoSuchMethodException unavailable) { /* 1.12 predates UnsafeValues#getDataVersion. */ }
            compound.getMethod("setInt", String.class, int.class).invoke(data, "DataVersion", version);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeCompressed.invoke(null, data, output);
            return output.toByteArray();
        }

        ItemStack deserialize(byte[] bytes) throws ReflectiveOperationException {
            Object data = readCompressed.invoke(null, new ByteArrayInputStream(bytes));
            Object item;
            try { item = nmsItem.getConstructor(compound).newInstance(data); }
            catch (NoSuchMethodException newerLegacy) { item = staticMethod(nmsItem, nmsItem, compound).invoke(null, data); }
            return bukkitItem(item);
        }

        Object readTag(ItemStack item, String key, String type) throws ReflectiveOperationException {
            Object tag = nmsItem.getMethod("getTag").invoke(nativeItem(item));
            if (tag == null || !Boolean.TRUE.equals(compound.getMethod("hasKey", String.class).invoke(tag, key))) return null;
            String getter = type.equals("STRING") ? "getString" : type.equals("LONG") ? "getLong" : "getDouble";
            return compound.getMethod(getter, String.class).invoke(tag, key);
        }

        void writeTag(ItemStack item, String key, String type, Object value) throws ReflectiveOperationException {
            Object nativeStack = nativeItem(item);
            Object tag = nmsItem.getMethod("getTag").invoke(nativeStack);
            if (tag == null) tag = compound.getConstructor().newInstance();
            String setter = type.equals("STRING") ? "setString" : type.equals("LONG") ? "setLong" : "setDouble";
            Class<?> valueType = type.equals("STRING") ? String.class : type.equals("LONG") ? long.class : double.class;
            compound.getMethod(setter, String.class, valueType).invoke(tag, key, value);
            nmsItem.getMethod("setTag", compound).invoke(nativeStack, tag);
            item.setItemMeta(bukkitItem(nativeStack).getItemMeta());
        }

        void spawnEgg(ItemStack item, String entity) throws ReflectiveOperationException {
            Object nativeStack = nativeItem(item);
            Object tag = nmsItem.getMethod("getTag").invoke(nativeStack);
            if (tag == null) tag = compound.getConstructor().newInstance();
            Object entityTag = compound.getConstructor().newInstance();
            compound.getMethod("setString", String.class, String.class).invoke(entityTag, "id", "minecraft:" + entity.toLowerCase(Locale.ROOT));
            Class<?> base = compound.getSuperclass();
            compound.getMethod("set", String.class, base).invoke(tag, "EntityTag", entityTag);
            nmsItem.getMethod("setTag", compound).invoke(nativeStack, tag);
            item.setItemMeta(bukkitItem(nativeStack).getItemMeta());
        }

        private static Method staticMethod(Class<?> owner, Class<?> result, Class<?>... parameters) throws NoSuchMethodException {
            for (Method method : owner.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == result
                        && java.util.Arrays.equals(method.getParameterTypes(), parameters)) return method;
            }
            throw new NoSuchMethodException(owner.getName() + " native codec method " + java.util.Arrays.toString(parameters));
        }
    }
}
