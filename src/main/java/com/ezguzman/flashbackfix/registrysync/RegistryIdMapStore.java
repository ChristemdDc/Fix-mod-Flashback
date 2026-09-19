package com.ezguzman.flashbackfix.registrysync;

import com.ezguzman.flashbackfix.ReplayGuard;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.RegistrySnapshot;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistencia del "mapa de IDs" de los registros sincronizados (item, block, fluid, entity_type,
 * etc.) tal como estaban ACTIVOS al momento de capturarlo. Mientras estás conectado a un servidor
 * NeoForge, los registros del cliente están remapeados a los IDs del servidor, así que capturar en
 * ese momento guarda exactamente el mapeo con el que se graban los replays.
 *
 * Formato: archivo binario propio (magic + versión + N x [nombre de registro + RegistrySnapshot
 * serializado con el STREAM_CODEC oficial de NeoForge]).
 */
public final class RegistryIdMapStore {

    private static final int MAGIC = 0x46424658; // "FBFX"
    // v3: captura por iteración de byId (inmune al robo del id default); v1/v2 se rechazan porque
    // la captura vieja (keySet+getId) dejaba entradas sin ID robando el id 0 del default en los
    // registros defaulted (block/item/fluid): p. ej. un bloque solo-cliente sobrescribía a
    // minecraft:air y todo el mundo del replay decodificaba como ese bloque.
    private static final int FORMAT_VERSION = 3;
    private static final String LATEST_FILE = "latest.bin";
    /** Estados por bucket para la firma del mapa de blockstates. */
    private static final int SIGNATURE_BUCKET = 4096;

    /** Mapa de IDs + firma del mapa de blockstates del servidor (null en archivos v1). */
    public record LoadedMap(Map<ResourceLocation, RegistrySnapshot> snapshots, BlockStateSignature signature) {}

    /** Firma por buckets del mapa global de blockstates: tamaño total + CRC32 por cada 4096 estados. */
    public record BlockStateSignature(int totalStates, long[] bucketCrcs) {}

    private RegistryIdMapStore() {}

    private static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve("flashbackfix").resolve("idmaps");
    }

    public static void save(Map<ResourceLocation, RegistrySnapshot> snapshots, String serverKey) {
        try {
            Files.createDirectories(directory());
            byte[] bytes = serialize(sanitizeSnapshots(snapshots), computeCurrentBlockStateSignature());
            Files.write(directory().resolve(sanitizeFileName(serverKey) + ".bin"), bytes);
            Files.write(directory().resolve(LATEST_FILE), bytes);
        } catch (IOException e) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo guardar el mapa de IDs del servidor", e);
        }
    }

    /** Devuelve el último mapa capturado (saneado), o null si no existe o no se puede leer. */
    public static LoadedMap loadLatest() {
        Path file = directory().resolve(LATEST_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            LoadedMap loaded = deserialize(Files.readAllBytes(file));
            return new LoadedMap(sanitizeSnapshots(loaded.snapshots()), loaded.signature());
        } catch (Exception e) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo leer el mapa de IDs guardado (se ignora)", e);
            return null;
        }
    }

    /**
     * Captura el mapeo ACTIVO id→nombre de todos los registros sincronizables, iterando cada
     * registro EN ORDEN DE ID (la posición de iteración de un MappedRegistry ES su id). A
     * diferencia de {@code RegistrySnapshot(registry, false)} (que itera keySet y pregunta el id
     * por nombre), este método es inmune al robo del id default: en registros defaulted
     * (block/item/fluid), una entrada SIN id (contenido solo-cliente tras la sync del servidor)
     * responde con el id del default (0 = air) y sobrescribía la entrada real. Aquí las entradas
     * sin id simplemente no aparecen (no están en byId), que es el estado correcto.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Map<ResourceLocation, RegistrySnapshot> captureActiveSnapshots() throws ReflectiveOperationException {
        if (!REFLECTION_READY) {
            throw new ReflectiveOperationException("reflexion sobre RegistrySnapshot no disponible");
        }
        Map<ResourceLocation, RegistrySnapshot> out = new HashMap<>();
        for (Registry registry : BuiltInRegistries.REGISTRY) {
            if (!registry.doesSync()) {
                continue;
            }
            RegistrySnapshot snap = emptySnapshotCtor.newInstance();
            Int2ObjectSortedMap<ResourceLocation> ids = (Int2ObjectSortedMap<ResourceLocation>) idsField.get(snap);
            int id = 0;
            for (Object value : (Iterable<Object>) registry) {
                ResourceLocation name = registry.getKey(value);
                if (name != null) {
                    ids.put(id, name);
                }
                id++;
            }
            out.put(registry.key().location(), snap);
        }
        return out;
    }

    /**
     * Firma del mapa global de blockstates ACTUAL ({@code Block.BLOCK_STATE_REGISTRY}), en orden de
     * ID: tamaño total + CRC32 por bucket de 4096 estados. Capturada conectado al servidor refleja
     * el mapa del servidor; recomputada tras el remapeo del replay permite verificar que la
     * reconstrucción coincide y, si no, localizar el primer punto divergente.
     */
    public static BlockStateSignature computeCurrentBlockStateSignature() {
        var idMap = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY;
        List<Long> crcs = new ArrayList<>();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        int count = 0;
        for (net.minecraft.world.level.block.state.BlockState state : idMap) {
            crc.update(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            count++;
            if (count % SIGNATURE_BUCKET == 0) {
                crcs.add(crc.getValue());
                crc.reset();
            }
        }
        if (count % SIGNATURE_BUCKET != 0) {
            crcs.add(crc.getValue());
        }
        long[] buckets = new long[crcs.size()];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = crcs.get(i);
        }
        return new BlockStateSignature(count, buckets);
    }

    /**
     * Compara la firma guardada contra el mapa de blockstates actual. Devuelve null si coinciden;
     * si no, una descripción del primer punto divergente (incluyendo el bloque local en esa zona).
     */
    public static String describeBlockStateMismatch(BlockStateSignature stored) {
        BlockStateSignature current = computeCurrentBlockStateSignature();
        if (stored.totalStates() == current.totalStates()
                && java.util.Arrays.equals(stored.bucketCrcs(), current.bucketCrcs())) {
            return null;
        }
        int firstBadBucket = -1;
        int buckets = Math.min(stored.bucketCrcs().length, current.bucketCrcs().length);
        for (int i = 0; i < buckets; i++) {
            if (stored.bucketCrcs()[i] != current.bucketCrcs()[i]) {
                firstBadBucket = i;
                break;
            }
        }
        String zone = "";
        if (firstBadBucket >= 0) {
            int stateId = firstBadBucket * SIGNATURE_BUCKET;
            var state = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(stateId);
            zone = String.format(" Primer bucket divergente #%d (estados %d..%d); bloque local en esa zona: %s.",
                    firstBadBucket, stateId, stateId + SIGNATURE_BUCKET - 1,
                    state == null ? "?" : BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        return String.format("tamano servidor=%d vs local=%d.%s", stored.totalStates(), current.totalStates(), zone);
    }

    // --- Saneado de snapshots ---
    // Al capturar en un cliente CONECTADO, las entradas de contenido solo-cliente (que el servidor
    // no conoce) quedaron sin ID numérico tras la sincronización de NeoForge: getId() devuelve -1 y
    // el snapshot las guarda así. applySnapshot con id=-1 revienta con ArrayIndexOutOfBounds, así
    // que se descartan (es exactamente el estado que tienen en vivo dentro del servidor).
    // RegistrySnapshot no es construible/mutable por API pública; se usa reflexión sobre su ctor
    // privado y el campo interno 'ids' (código propio de NeoForge, nombres estables).

    private static Constructor<RegistrySnapshot> emptySnapshotCtor;
    private static Field idsField;
    private static Field aliasesField;
    private static final boolean REFLECTION_READY = initReflection();

    private static boolean initReflection() {
        try {
            emptySnapshotCtor = RegistrySnapshot.class.getDeclaredConstructor();
            emptySnapshotCtor.setAccessible(true);
            idsField = RegistrySnapshot.class.getDeclaredField("ids");
            idsField.setAccessible(true);
            aliasesField = RegistrySnapshot.class.getDeclaredField("aliases");
            aliasesField.setAccessible(true);
            return true;
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Reflexion sobre RegistrySnapshot no disponible; "
                    + "no se podran sanear mapas de IDs", t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, RegistrySnapshot> sanitizeSnapshots(Map<ResourceLocation, RegistrySnapshot> snapshots) {
        if (!REFLECTION_READY) {
            return snapshots;
        }
        Map<ResourceLocation, RegistrySnapshot> out = new HashMap<>();
        int dropped = 0;
        try {
            for (Map.Entry<ResourceLocation, RegistrySnapshot> entry : snapshots.entrySet()) {
                RegistrySnapshot clean = emptySnapshotCtor.newInstance();
                Int2ObjectSortedMap<ResourceLocation> ids =
                        (Int2ObjectSortedMap<ResourceLocation>) idsField.get(clean);
                for (var idEntry : entry.getValue().getIds().int2ObjectEntrySet()) {
                    if (idEntry.getIntKey() >= 0) {
                        ids.put(idEntry.getIntKey(), idEntry.getValue());
                    } else {
                        dropped++;
                    }
                }
                ((Map<ResourceLocation, ResourceLocation>) aliasesField.get(clean))
                        .putAll(entry.getValue().getAliases());
                out.put(entry.getKey(), clean);
            }
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo sanear el mapa de IDs; se usa tal cual", t);
            return snapshots;
        }
        if (dropped > 0) {
            ReplayGuard.LOGGER.info("[FlashbackFix] Saneado el mapa de IDs: descartadas {} entrada(s) sin ID "
                    + "(contenido solo-cliente que el servidor no conoce).", dropped);
        }
        return out;
    }

    private static byte[] serialize(Map<ResourceLocation, RegistrySnapshot> snapshots, BlockStateSignature signature) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeInt(MAGIC);
            buf.writeVarInt(FORMAT_VERSION);
            buf.writeVarInt(snapshots.size());
            for (Map.Entry<ResourceLocation, RegistrySnapshot> entry : snapshots.entrySet()) {
                buf.writeResourceLocation(entry.getKey());
                RegistrySnapshot.STREAM_CODEC.encode(buf, entry.getValue());
            }
            buf.writeVarInt(signature.totalStates());
            buf.writeVarInt(signature.bucketCrcs().length);
            for (long bucket : signature.bucketCrcs()) {
                buf.writeLong(bucket);
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static LoadedMap deserialize(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            if (buf.readInt() != MAGIC) {
                throw new IllegalStateException("cabecera invalida");
            }
            int version = buf.readVarInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("formato v" + version + " obsoleto/desconocido (se requiere v"
                        + FORMAT_VERSION + "); entra al servidor de nuevo para regenerar el mapa");
            }
            int count = buf.readVarInt();
            Map<ResourceLocation, RegistrySnapshot> map = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                ResourceLocation name = buf.readResourceLocation();
                map.put(name, RegistrySnapshot.STREAM_CODEC.decode(buf));
            }
            BlockStateSignature signature = null;
            if (version >= 2) {
                int totalStates = buf.readVarInt();
                long[] buckets = new long[buf.readVarInt()];
                for (int i = 0; i < buckets.length; i++) {
                    buckets[i] = buf.readLong();
                }
                signature = new BlockStateSignature(totalStates, buckets);
            }
            return new LoadedMap(map, signature);
        } finally {
            buf.release();
        }
    }

    /** Resultado de comparar un mapa guardado contra los registros ACTIVOS actuales. */
    public record Comparison(int totalEntries, int differing, List<ResourceLocation> missing,
                             List<ResourceLocation> unknownRegistries) {
        public boolean isSafeToApply() {
            return missing.isEmpty() && unknownRegistries.isEmpty();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Comparison compareToCurrent(Map<ResourceLocation, RegistrySnapshot> snapshots) {
        int total = 0;
        int differing = 0;
        List<ResourceLocation> missing = new ArrayList<>();
        List<ResourceLocation> unknownRegistries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, RegistrySnapshot> entry : snapshots.entrySet()) {
            Registry registry = BuiltInRegistries.REGISTRY.get(entry.getKey());
            var ids = entry.getValue().getIds();
            if (registry == null) {
                if (!ids.isEmpty()) {
                    unknownRegistries.add(entry.getKey());
                }
                continue;
            }
            for (var idEntry : ids.int2ObjectEntrySet()) {
                total++;
                ResourceLocation name = idEntry.getValue();
                // containsKey y no get(): en registros con default (item/block), get() devuelve
                // el default (air) para nombres inexistentes y ocultaria los faltantes.
                if (!registry.containsKey(name)) {
                    missing.add(name);
                } else if (registry.getId(registry.get(name)) != idEntry.getIntKey()) {
                    differing++;
                }
            }
        }
        return new Comparison(total, differing, missing, unknownRegistries);
    }

    private static String sanitizeFileName(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
