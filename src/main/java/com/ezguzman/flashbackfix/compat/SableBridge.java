package com.ezguzman.flashbackfix.compat;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Puente de grabación/reproducción para los paquetes de red de Sable (naves/sublevels).
 *
 * Sable transporta sus paquetes por la API de red de Veil (canal propio), invisible para
 * Flashback: por eso las naves no aparecían en los replays (verificado forensemente: cero
 * paquetes de sable en el stream grabado). Este puente:
 *
 *  - GRABACIÓN ({@link #capture}): al ejecutarse el handle() de un paquete de Sable en el cliente
 *    (mixin), si Flashback está grabando, re-serializa el paquete con su CODEC público y lo escribe
 *    en la grabación envuelto en {@link SableBridgePayload} vía Recorder.writePacketAsync.
 *  - REPRODUCCIÓN ({@link #replayDispatch}): el payload grabado llega por el forward normal del
 *    replay; se decodifica el paquete original y se re-entrega a Sable (handleClient(Level) si
 *    existe, o handle(PacketContext) con un proxy dinámico del contexto de Veil).
 */
public final class SableBridge {

    /** Solo estas clases se capturan/re-entregan (allowlist de seguridad). */
    private static final Set<String> ALLOWED = Set.of(
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeBoundsSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopMovingSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundFloatingBlockMaterialPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundPhysicsPropertyPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeSubLevelNamePacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundRecentlySplitSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket",
            "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket");

    private static final Map<String, StreamCodec<RegistryFriendlyByteBuf, Object>> CODECS = new ConcurrentHashMap<>();
    private static final Set<String> warnedCapture = ConcurrentHashMap.newKeySet();
    private static final Set<String> warnedDispatch = ConcurrentHashMap.newKeySet();
    private static volatile Field recorderField;
    private static volatile Method writePacketAsync;
    private static boolean loggedCapture = false;
    private static boolean loggedDispatch = false;

    private SableBridge() {}

    @SuppressWarnings("unchecked")
    private static StreamCodec<RegistryFriendlyByteBuf, Object> codecOf(Class<?> packetClass) throws Exception {
        StreamCodec<RegistryFriendlyByteBuf, Object> codec = CODECS.get(packetClass.getName());
        if (codec == null) {
            codec = (StreamCodec<RegistryFriendlyByteBuf, Object>) packetClass.getField("CODEC").get(null);
            CODECS.put(packetClass.getName(), codec);
        }
        return codec;
    }

    private static Object activeRecorder() throws Exception {
        if (recorderField == null) {
            recorderField = Class.forName("com.moulberry.flashback.Flashback").getField("RECORDER");
        }
        return recorderField.get(null);
    }

    // --- Cache de ESTADO INICIAL por sublevel ---
    // Las naves ya ensambladas/trackeadas ANTES de empezar a grabar nunca envían StartTracking
    // durante la grabación (el cliente ya las conoce) → el replay recibía solo snapshots de una
    // nave jamás creada. Cacheamos los bytes de los paquetes de estado (StartTracking, Finalize,
    // bounds, nombre, propiedades) POR SUBLEVEL cuando llegan (en cualquier momento de la sesión)
    // y los re-emitimos en cada keyframe de la grabación (appendInitialStates). El id del sublevel
    // se extrae del primer record component (long) del paquete.
    private static final Set<String> STATE_CLASSES = Set.of(
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeBoundsSubLevelPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeSubLevelNamePacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundPhysicsPropertyPacket",
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundFloatingBlockMaterialPacket");
    private static final String START_TRACKING =
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket";
    private static final String STOP_TRACKING =
            "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket";
    /** classId → (subLevelId → bytes del último paquete de ese tipo). */
    private static final Map<String, Map<Long, byte[]>> initialState = new ConcurrentHashMap<>();
    /** UUIDs de los sublevels cuyo StartTracking original ya tenemos cacheado. */
    private static final Set<java.util.UUID> cachedStartTrackingIds = ConcurrentHashMap.newKeySet();

    private static Long subLevelIdOf(Object packet) {
        try {
            var components = packet.getClass().getRecordComponents();
            if (components != null && components.length > 0 && components[0].getType() == long.class) {
                return (Long) components[0].getAccessor().invoke(packet);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] encodePacket(Object packet, Minecraft mc) throws Exception {
        StreamCodec<RegistryFriendlyByteBuf, Object> codec = codecOf(packet.getClass());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        try {
            codec.encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /** Re-emite el estado inicial cacheado de cada sublevel trackeado (para cada keyframe). */
    public static void appendInitialStates(java.util.function.Consumer<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> consumer) {
        try {
            // StartTracking primero; el resto después (orden de creación correcto).
            int emitted = 0;
            Map<Long, byte[]> starts = initialState.get(START_TRACKING);
            if (starts == null || starts.isEmpty()) {
                return;
            }
            for (var e : starts.entrySet()) {
                consumer.accept(new ClientboundCustomPayloadPacket(new SableBridgePayload(START_TRACKING, e.getValue())));
                emitted++;
                for (String cls : STATE_CLASSES) {
                    if (cls.equals(START_TRACKING)) continue;
                    Map<Long, byte[]> byId = initialState.get(cls);
                    byte[] extra = byId == null ? null : byId.get(e.getKey());
                    if (extra != null) {
                        consumer.accept(new ClientboundCustomPayloadPacket(new SableBridgePayload(cls, extra)));
                        emitted++;
                    }
                }
            }
            // Una nave puede estar hecha de VARIOS sublevels (p. ej. la cola que gira es otro
            // sublevel). Si alguno está vivo en el cliente pero nunca vimos su StartTracking (llegó
            // antes de esta sesión, o nació de una división), lo sintetizamos desde su estado actual:
            // sin él, sus chunks llegan al replay y se descartan porque su plot no existe.
            int synthesized = appendMissingStartTracking(consumer);
            emitted += synthesized;
            int chunks = appendPlotChunks(consumer);
            int entities = appendPlotEntities(consumer);
            ReplayGuard.LOGGER.info("[FlashbackFix] Keyframe enriquecido con el estado inicial de {} sublevel(s) "
                    + "de Sable ({} paquetes de estado, {} sublevel(s) reconstruidos + {} chunk(s) de plot con "
                    + "bloques + {} entidad(es) del plot).",
                    starts.size() + synthesized, emitted, synthesized, chunks, entities);
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Fallo re-emitiendo estado inicial de sublevels", t);
        }
    }

    /**
     * Emite un StartTracking reconstruido para cada sublevel vivo en el cliente del que no tengamos
     * el paquete original cacheado. El paquete es un record público:
     * {@code (long plotCoordinate, UUID subLevelID, Pose3dc lastPose, Pose3d pose, BoundingBox3ic
     * bounds, String name, int gameTick)}, y Sable lo usa para crear el plot en
     * {@code allocateSubLevel(uuid, ChunkPos.getX(plotCoordinate), ChunkPos.getZ(plotCoordinate), pose)}.
     */
    private static int appendMissingStartTracking(
            java.util.function.Consumer<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> consumer) {
        int synthesized = 0;
        try {
            Minecraft mc = Minecraft.getInstance();
            Object container = plotContainerOf(mc.level);
            if (container == null) {
                return 0;
            }
            var subLevels = (java.util.List<?>) container.getClass().getMethod("getAllSubLevels").invoke(container);
            for (Object sub : subLevels) {
                Object plot = sub.getClass().getMethod("getPlot").invoke(sub);
                if (plot == null) {
                    continue;
                }
                var uuid = (java.util.UUID) sub.getClass().getMethod("getUniqueId").invoke(sub);
                if (cachedStartTrackingIds.contains(uuid)) {
                    continue;   // ya viaja su StartTracking original
                }
                var plotPos = (net.minecraft.world.level.ChunkPos)
                        plot.getClass().getField("plotPos").get(plot);
                Object packet = synthesizeStartTracking(sub, plot, plotPos.toLong(), mc);
                if (packet == null) {
                    continue;
                }
                consumer.accept(new ClientboundCustomPayloadPacket(
                        new SableBridgePayload(START_TRACKING, encodePacket(packet, mc))));
                synthesized++;
                if (warnedCapture.add("synth-" + uuid)) {
                    ReplayGuard.LOGGER.info("[FlashbackFix] Sublevel '{}' ({}) sin StartTracking grabado: se "
                            + "reconstruye desde su estado actual para que se vea en el replay.",
                            sub.getClass().getMethod("getName").invoke(sub), uuid);
                }
            }
            ReplayGuard.LOGGER.info("[FlashbackFix] Sublevels vivos en el cliente: {} ({} con StartTracking grabado, "
                    + "{} reconstruidos).", subLevels.size(), cachedStartTrackingIds.size(), synthesized);
        } catch (Throwable t) {
            if (warnedCapture.add("synth-start-tracking")) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] Fallo reconstruyendo el StartTracking de un sublevel", t);
            }
        }
        return synthesized;
    }

    private static Object synthesizeStartTracking(Object subLevel, Object plot, long plotCoordinate, Minecraft mc) {
        try {
            Class<?> cls = Class.forName(START_TRACKING);
            var ctor = cls.getConstructors()[0];
            Object lastPose = subLevel.getClass().getMethod("lastPose").invoke(subLevel);
            Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            Object bounds = plot.getClass().getMethod("getBoundingBox").invoke(plot);
            String name = (String) subLevel.getClass().getMethod("getName").invoke(subLevel);
            int gameTick = (int) mc.level.getGameTime();
            return ctor.newInstance(plotCoordinate, subLevel.getClass().getMethod("getUniqueId").invoke(subLevel),
                    lastPose, pose, bounds, name == null ? "" : name, gameTick);
        } catch (Throwable t) {
            if (warnedCapture.add("synth-ctor")) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo construir un StartTracking sintetico", t);
            }
            return null;
        }
    }

    /** classId sintético: paquete de chunk vanilla del plot, envuelto para saltarse al ReplayServer. */
    private static final String PLOT_CHUNK_PACKET = "flashbackfix:plot_chunk_packet";
    /** classId sintético: solo los bloques del chunk (sin block entities), formato propio. */
    private static final String PLOT_CHUNK_BLOCKS = "flashbackfix:plot_chunk_blocks";
    /** classId sintético: creación y datos de una entidad de dentro del plot (hélices, timones...). */
    private static final String PLOT_ENTITY_ADD = "flashbackfix:plot_entity_add";
    private static final String PLOT_ENTITY_DATA = "flashbackfix:plot_entity_data";
    /** classId sintético: datos custom de la entidad (la contraption en sí). */
    private static final String PLOT_ENTITY_SPAWN = "flashbackfix:plot_entity_spawn";
    /** classId sintético: quién va montado en la entidad (jugadores sentados). */
    private static final String PLOT_ENTITY_PASSENGERS = "flashbackfix:plot_entity_passengers";
    /** Tope de chunks por keyframe (una nave ocupa unos pocos; evita inflar la grabación). */
    private static final int MAX_PLOT_CHUNKS = 64;
    private static final int MAX_PLOT_ENTITIES = 128;

    /**
     * Los BLOQUES de una nave no viven en los chunks normales: viven en el "plot", una región de
     * chunks lejana que el servidor solo streamea a quien trackea el sublevel. El mundo del replay NO
     * los tiene (verificado: allí esa zona se GENERA como terreno normal), así que los escribimos
     * nosotros en el keyframe, leyéndolos del cliente mientras grabas.
     *
     * Van envueltos en nuestro payload y no como paquetes de chunk sueltos: así viajan por el mismo
     * canal que ya funciona para los paquetes de Sable (entrega directa al cliente) en vez de que el
     * ReplayServer se los quede.
     */
    private static int appendPlotChunks(java.util.function.Consumer<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> consumer) {
        int emitted = 0;
        int fallback = 0;
        int failed = 0;
        try {
            Minecraft mc = Minecraft.getInstance();
            Object container = plotContainerOf(mc.level);
            if (container == null) {
                return 0;
            }
            for (Object sub : (java.util.List<?>) container.getClass().getMethod("getAllSubLevels").invoke(container)) {
                Object plot = sub.getClass().getMethod("getPlot").invoke(sub);
                if (plot == null) {
                    continue;
                }
                var lightEngine = (net.minecraft.world.level.lighting.LevelLightEngine)
                        plot.getClass().getMethod("getLightEngine").invoke(plot);
                var holders = (java.util.Collection<?>) plot.getClass().getMethod("getLoadedChunks").invoke(plot);
                for (Object holder : holders) {
                    if (emitted + fallback >= MAX_PLOT_CHUNKS) {
                        break;
                    }
                    var chunk = (net.minecraft.world.level.chunk.LevelChunk)
                            holder.getClass().getMethod("getChunk").invoke(holder);
                    if (!hasBlocks(chunk)) {
                        continue;
                    }
                    net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket pkt = null;
                    try {
                        pkt = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                chunk, lightEngine, null, null);
                    } catch (Throwable t) {
                        // Algunos block entities modded no se pueden serializar desde el cliente
                        // (p. ej. el quemador de globo de Aeronautics: su versión cliente no encaja
                        // en el write() del servidor). Se aparta SOLO ese y se reintenta, para no
                        // perder el resto del chunk (los tanques/tuberías de Create guardan ahí su
                        // contenido, y la luz viaja en este mismo paquete).
                        pkt = buildChunkPacketSkippingBadBlockEntities(chunk, lightEngine, mc);
                    }
                    if (pkt != null) {
                        consumer.accept(new ClientboundCustomPayloadPacket(
                                new SableBridgePayload(PLOT_CHUNK_PACKET, encodeChunkPacket(pkt, mc))));
                        emitted++;
                        continue;
                    }
                    try {
                        consumer.accept(new ClientboundCustomPayloadPacket(
                                new SableBridgePayload(PLOT_CHUNK_BLOCKS, encodeChunkBlocks(chunk))));
                        fallback++;
                    } catch (Throwable t2) {
                        failed++;
                        if (warnedCapture.add("plot-chunk-" + chunk.getPos())) {
                            ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo escribir el chunk {} del plot "
                                    + "en el keyframe", chunk.getPos(), t2);
                        }
                    }
                }
            }
            if (fallback > 0 || failed > 0) {
                ReplayGuard.LOGGER.info("[FlashbackFix] Chunks del plot en el keyframe: {} completos, {} solo-bloques "
                        + "(block entity modded no serializable), {} perdidos.", emitted, fallback, failed);
            }
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Fallo escribiendo los chunks del plot en el keyframe", t);
        }
        return emitted + fallback;
    }

    /**
     * Reintenta construir el paquete de chunk apartando temporalmente los block entities que no
     * saben serializarse en el cliente. Devuelve null si el fallo no venía de ninguno de ellos.
     */
    private static net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
            buildChunkPacketSkippingBadBlockEntities(net.minecraft.world.level.chunk.LevelChunk chunk,
                                                     net.minecraft.world.level.lighting.LevelLightEngine lightEngine,
                                                     Minecraft mc) {
        var blockEntities = chunk.getBlockEntities();
        var bad = new java.util.HashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.entity.BlockEntity>();
        var registries = mc.level.registryAccess();
        for (var entry : new java.util.ArrayList<>(blockEntities.entrySet())) {
            try {
                entry.getValue().getUpdateTag(registries);
            } catch (Throwable t) {
                bad.put(entry.getKey(), entry.getValue());
                if (warnedCapture.add("bad-be-" + entry.getValue().getClass().getName())) {
                    ReplayGuard.LOGGER.info("[FlashbackFix] Block entity que no se puede serializar desde el "
                            + "cliente, se omite en los chunks de la nave: {}", entry.getValue().getClass().getName());
                }
            }
        }
        if (bad.isEmpty()) {
            return null;
        }
        try {
            bad.keySet().forEach(blockEntities::remove);
            return new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                    chunk, lightEngine, null, null);
        } catch (Throwable t) {
            return null;
        } finally {
            blockEntities.putAll(bad);
        }
    }

    /**
     * Las piezas móviles de una nave (hélices, timones, pegamento) son ENTIDADES que viven dentro del
     * plot, a ~20 millones de bloques. El servidor de reproducción nunca se las envía al espectador:
     * el seguimiento de entidades es por distancia y sus chunks ni siquiera están cargados allí, así
     * que jamás llegan a registrarse para seguimiento. Igual que con los chunks, las capturamos del
     * cliente al grabar y las re-inyectamos nosotros.
     */
    private static int appendPlotEntities(java.util.function.Consumer<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> consumer) {
        int emitted = 0;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return 0;
            }
            for (var entity : mc.level.entitiesForRendering()) {
                if (emitted >= MAX_PLOT_ENTITIES) {
                    break;
                }
                if (entity instanceof net.minecraft.world.entity.player.Player || !isInsidePlot(entity)) {
                    continue;
                }
                try {
                    var add = new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(
                            entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                            entity.getXRot(), entity.getYRot(), entity.getType(), 0,
                            entity.getDeltaMovement(), entity.getYHeadRot());
                    consumer.accept(new ClientboundCustomPayloadPacket(new SableBridgePayload(
                            PLOT_ENTITY_ADD, encodeWith(
                                    net.minecraft.network.protocol.game.ClientboundAddEntityPacket.STREAM_CODEC,
                                    add, mc))));
                    var values = entity.getEntityData().getNonDefaultValues();
                    if (values != null && !values.isEmpty()) {
                        var data = new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(
                                entity.getId(), values);
                        consumer.accept(new ClientboundCustomPayloadPacket(new SableBridgePayload(
                                PLOT_ENTITY_DATA, encodeWith(
                                        net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC,
                                        data, mc))));
                    }
                    // Los datos custom (la contraption en sí) van detrás, ya con la entidad creada, y
                    // TAMBIÉN envueltos: Flashback descarta los payloads normales al reproducir, y sin
                    // ellos la contraption llega vacía (entidad creada pero sin un solo bloque que
                    // dibujar — justo lo que le pasaba a la cola de la aeronave).
                    if (entity instanceof net.neoforged.neoforge.entity.IEntityWithComplexSpawn) {
                        var advanced = new net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload(entity);
                        var buf = new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
                        byte[] bytes;
                        try {
                            buf.writeVarInt(advanced.entityId());
                            buf.writeByteArray(advanced.customPayload());
                            bytes = new byte[buf.readableBytes()];
                            buf.readBytes(bytes);
                        } finally {
                            buf.release();
                        }
                        consumer.accept(new ClientboundCustomPayloadPacket(
                                new SableBridgePayload(PLOT_ENTITY_SPAWN, bytes)));
                    }
                    // Quien va montado (p. ej. sentado en una silla de la nave) viaja aparte: sin
                    // esto el jugador aparece DE PIE en el replay aunque estuviera sentado.
                    if (!entity.getPassengers().isEmpty()) {
                        var riders = new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(entity);
                        consumer.accept(new ClientboundCustomPayloadPacket(new SableBridgePayload(
                                PLOT_ENTITY_PASSENGERS, encodeWith(
                                        net.minecraft.network.protocol.game.ClientboundSetPassengersPacket.STREAM_CODEC,
                                        riders, mc))));
                    }
                    emitted++;
                } catch (Throwable t) {
                    if (warnedCapture.add("plot-entity-" + entity.getType())) {
                        ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo escribir la entidad {} del plot en el "
                                + "keyframe", entity.getType(), t);
                    }
                }
            }
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Fallo escribiendo las entidades del plot en el keyframe", t);
        }
        return emitted;
    }

    private static <T> byte[] encodeWith(net.minecraft.network.codec.StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                         T value, Minecraft mc) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        try {
            codec.encode(buf, value);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    // --- Restauración continua de las piezas de la nave ---
    // Los payloads del keyframe solo llegan al pasar por él, pero el replay destruye las entidades
    // en cada salto de la línea de tiempo (y a veces también al re-sincronizar). Guardamos lo
    // recibido y, si una pieza falta, la volvemos a crear en el siguiente tick: así la nave aguanta
    // adelantar y retroceder tantas veces como haga falta.
    private static final Map<Integer, byte[]> plotEntityAdds = new ConcurrentHashMap<>();
    private static final Map<Integer, byte[]> plotEntityData = new ConcurrentHashMap<>();
    private static final Map<Integer, byte[]> plotEntitySpawns = new ConcurrentHashMap<>();
    private static final Map<Integer, java.util.UUID> plotEntityUuids = new ConcurrentHashMap<>();
    private static final Map<Integer, byte[]> plotEntityPassengers = new ConcurrentHashMap<>();
    private static int restoreTickCounter = 0;

    /** Llamado cada tick del cliente: recrea las piezas de la nave que hayan desaparecido. */
    public static void tickRestorePlotEntities() {
        if (++restoreTickCounter % 10 != 0 || (plotEntityAdds.isEmpty() && plotEntitySpawns.isEmpty())) {
            return;
        }
        if (!ReplayDetector.isReplayServerActive()) {
            if (!plotEntityAdds.isEmpty() || !plotEntitySpawns.isEmpty()) {
                plotEntityAdds.clear();
                plotEntityData.clear();
                plotEntitySpawns.clear();
                plotEntityUuids.clear();
                plotEntityPassengers.clear();
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            return;
        }
        int restored = 0;
        int refilled = 0;
        int idTaken = 0;
        for (var entry : plotEntityAdds.entrySet()) {
            int id = entry.getKey();
            try {
                var existing = mc.level.getEntity(id);
                var expectedId = plotEntityUuids.get(id);
                if (existing != null && expectedId != null && !expectedId.equals(existing.getUUID())) {
                    idTaken++;   // otra entidad ocupa ese hueco: no se puede recrear con ese id
                    continue;
                }
                if (existing != null) {
                    // Está, pero puede haber quedado VACÍA (sin su contraption): se rellena.
                    if (looksEmpty(existing)) {
                        byte[] spawn = plotEntitySpawns.get(id);
                        if (spawn != null && applySpawnData(existing, spawn, mc)) {
                            refilled++;
                        }
                    }
                    continue;
                }
                injectEntityPacket(PLOT_ENTITY_ADD, entry.getValue(), mc);
                byte[] data = plotEntityData.get(id);
                if (data != null) {
                    injectEntityPacket(PLOT_ENTITY_DATA, data, mc);
                }
                byte[] spawn = plotEntitySpawns.get(id);
                if (spawn != null) {
                    injectEntitySpawnData(spawn, mc);
                }
                byte[] riders = plotEntityPassengers.get(id);
                if (riders != null) {
                    injectPassengers(riders, mc);   // vuelve a sentar a quien iba montado
                }
                restored++;
            } catch (Throwable t) {
                if (warnedDispatch.add("restore-" + id)) {
                    ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo restaurar la pieza {} de la nave", id, t);
                }
            }
        }
        if (restored > 0 || refilled > 0 || idTaken > 0) {
            ReplayGuard.notePlotEntitiesRestored(restored, refilled, idTaken);
        }
        // Datos custom pendientes: la entidad puede no existir todavia cuando llegan (el keyframe va
        // por delante de su spawn) o ser una instancia nueva tras un salto. Se reintenta hasta que
        // se aplican; el guard por instancia evita repetirlo sobre la misma.
        for (var entry : plotEntitySpawns.entrySet()) {
            try {
                var entity = mc.level.getEntity(entry.getKey());
                if (entity instanceof net.neoforged.neoforge.entity.IEntityWithComplexSpawn complex
                        && !ReplayGuard.hasSpawnDataApplied(complex)) {
                    injectEntitySpawnData(entry.getValue(), mc);
                }
            } catch (Throwable ignored) {
                // Entidad en un estado raro: se reintentara en el siguiente tick.
            }
        }
    }

    /** ¿La pieza está presente pero sin contenido (contraption nula)? Entonces no se dibuja nada. */
    private static boolean looksEmpty(Object entity) {
        try {
            return entity.getClass().getMethod("getContraption").invoke(entity) == null;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean applySpawnData(Object entity, byte[] data, Minecraft mc) {
        if (!(entity instanceof net.neoforged.neoforge.entity.IEntityWithComplexSpawn complex)) {
            return false;
        }
        var reader = new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        byte[] custom;
        try {
            reader.readVarInt();
            custom = reader.readByteArray();
        } finally {
            reader.release();
        }
        RegistryFriendlyByteBuf payload = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(custom), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        try {
            complex.readSpawnData(payload);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            payload.release();
        }
    }

    /** Reproducción: re-inyecta un paquete de entidad del plot directamente en el cliente. */
    private static void injectEntityPacket(String classId, byte[] data, Minecraft mc) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(data), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> pkt;
        try {
            pkt = PLOT_ENTITY_ADD.equals(classId)
                    ? net.minecraft.network.protocol.game.ClientboundAddEntityPacket.STREAM_CODEC.decode(buf)
                    : net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
        if (PLOT_ENTITY_ADD.equals(classId)) {
            int id = ((net.minecraft.network.protocol.game.ClientboundAddEntityPacket) pkt).getId();
            if (chunkGenerationLevel != mc.level) {
                chunkGenerationLevel = mc.level;
                resetInjectedChunks();
            }
            // Se comprueba si la entidad EXISTE ahora mismo en vez de recordar que ya se inyectó:
            // al saltar por la línea de tiempo, Flashback destruye y recrea las entidades, y un
            // registro de "ya inyectada" impedía volver a crearlas (la nave perdía sus piezas al
            // adelantar o retroceder). Así se recrean solas cuando hacen falta.
            plotEntityAdds.put(id, data);
            plotEntityUuids.put(id, ((net.minecraft.network.protocol.game.ClientboundAddEntityPacket) pkt).getUUID());
            if (mc.level.getEntity(id) != null) {
                return;
            }
        } else {
            plotEntityData.put(((net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket) pkt).id(), data);
        }
        var connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        pkt.handle(connection);
        if (PLOT_ENTITY_ADD.equals(classId)) {
            var added = (net.minecraft.network.protocol.game.ClientboundAddEntityPacket) pkt;
            var created = mc.level.getEntity(added.getId());
            if (warnedDispatch.add("plot-entity-check-" + added.getType())) {
                boolean rendered = false;
                for (var e : mc.level.entitiesForRendering()) {
                    if (e == created) {
                        rendered = true;
                        break;
                    }
                }
                ReplayGuard.LOGGER.info("[FlashbackFix] Entidad del plot re-inyectada: tipo={} creada={} "
                        + "en_lista_de_render={} pos={}", added.getType(), created != null, rendered,
                        created == null ? "-" : created.position());
            }
            ReplayGuard.notePlotEntityTracked(created);
        }
    }

    /**
     * Reproducción: vuelve a montar a quien iba encima (jugadores sentados en la nave). Se guarda
     * ademas para poder restaurarlo si el replay recrea la entidad al saltar en la linea de tiempo.
     */
    private static void injectPassengers(byte[] data, Minecraft mc) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(data), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        net.minecraft.network.protocol.game.ClientboundSetPassengersPacket pkt;
        try {
            pkt = net.minecraft.network.protocol.game.ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
        plotEntityPassengers.put(pkt.getVehicle(), data);
        var connection = mc.getConnection();
        if (connection != null && mc.level.getEntity(pkt.getVehicle()) != null) {
            pkt.handle(connection);
            ReplayGuard.notePassengersRestored(pkt.getPassengers().length);
        }
    }

    // --- Retroceso en la línea de tiempo ---
    // El movimiento de las naves va en muestras numeradas por tick del servidor original. Sable las
    // acumula en un buffer y avanza un puntero que solo va hacia delante, asi que al RETROCEDER las
    // muestras antiguas quedan detras del puntero y la nave se queda clavada en la ultima posicion
    // alcanzada. Cuando detectamos que el tick recibido retrocede, vaciamos el buffer y reiniciamos
    // el reloj de interpolacion para que la nave vuelva a seguir la grabacion desde ese punto.
    private static volatile int lastSnapshotTick = Integer.MIN_VALUE;

    private static void noteSnapshotTick(Object packet, Minecraft mc) {
        Integer tick = tickOf(packet);
        if (tick == null) {
            return;
        }
        int previous = lastSnapshotTick;
        lastSnapshotTick = tick;
        if (previous != Integer.MIN_VALUE && tick < previous - 2) {
            resetInterpolation(mc);
        }
    }

    private static Integer tickOf(Object packet) {
        for (String name : new String[]{"interpolationTick", "gameTick"}) {
            try {
                Field field = packet.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(packet);
            } catch (NoSuchFieldException ignored) {
                // El paquete no lleva tick: se prueba el siguiente nombre.
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void resetInterpolation(Minecraft mc) {
        try {
            Object container = plotContainerOf(mc.level);
            if (container == null) {
                return;
            }
            for (Object sub : (java.util.List<?>) container.getClass().getMethod("getAllSubLevels").invoke(container)) {
                Object interpolator = sub.getClass().getMethod("getInterpolator").invoke(sub);
                if (interpolator == null) {
                    continue;
                }
                Object buffer = interpolator.getClass().getField("buffer").get(interpolator);
                buffer.getClass().getMethod("clear").invoke(buffer);
            }
            Object state = container.getClass().getMethod("getInterpolation").invoke(container);
            if (state != null) {
                setPrivate(state, "receivedFirstUpdate", false);
                setPrivate(state, "stopped", false);
                setPrivate(state, "mostRecentTick", 0.0D);
                setPrivate(state, "interpolationTick", 0.0D);
            }
            ReplayGuard.noteInterpolationReset();
        } catch (Throwable t) {
            if (warnedDispatch.add("interp-reset")) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo reiniciar la interpolacion de las naves al "
                        + "retroceder en la linea de tiempo", t);
            }
        }
    }

    private static void setPrivate(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable ignored) {
            // Campo renombrado en otra version de Sable: el resto del reinicio sigue siendo util.
        }
    }

    /**
     * Empaqueta los datos custom de una entidad (la contraption con todos sus bloques) en NUESTRO
     * canal. Lo usa el enriquecedor de keyframes para CUALQUIER entidad compleja, no solo las de la
     * nave: enviados como payload normal, Flashback los descarta al reproducir y las contraptions se
     * reconstruyen vacias (invisibles).
     */
    public static Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>
            wrapSpawnData(net.minecraft.world.entity.Entity entity) {
        var advanced = new net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload(entity);
        var buf = new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
        byte[] bytes;
        try {
            buf.writeVarInt(advanced.entityId());
            buf.writeByteArray(advanced.customPayload());
            bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
        } finally {
            buf.release();
        }
        return new ClientboundCustomPayloadPacket(new SableBridgePayload(PLOT_ENTITY_SPAWN, bytes));
    }

    /**
     * Reproducción: aplica a la entidad sus datos custom (la contraption con todos sus bloques). Se
     * hace directamente sobre la entidad en vez de pasar por el handler de NeoForge, porque el
     * payload viene por nuestro canal.
     */
    private static void injectEntitySpawnData(byte[] data, Minecraft mc) {
        var buf = new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        int entityId;
        byte[] custom;
        try {
            entityId = buf.readVarInt();
            custom = buf.readByteArray();
        } finally {
            buf.release();
        }
        plotEntitySpawns.put(entityId, data);
        var entity = mc.level.getEntity(entityId);
        if (!(entity instanceof net.neoforged.neoforge.entity.IEntityWithComplexSpawn complex)) {
            return;
        }
        // Una vez por INSTANCIA de entidad, no por id: repetirlo en cada keyframe corrompe la
        // contraption, pero tras un salto la entidad es otra instancia y sí debe recibir sus datos.
        if (ReplayGuard.hasSpawnDataApplied(complex)) {
            return;
        }
        RegistryFriendlyByteBuf payload = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(custom), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        try {
            complex.readSpawnData(payload);
            ReplayGuard.markSpawnDataApplied(complex);
            ReplayGuard.noteSpawnDataApplied(entity);
        } catch (Throwable t) {
            if (warnedDispatch.add("plot-spawn-" + entity.getClass().getName())) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudieron aplicar los datos custom de {} en el replay",
                        entity.getClass().getSimpleName(), t);
            }
        } finally {
            payload.release();
        }
    }

    private static byte[] encodeChunkPacket(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket pkt,
                                            Minecraft mc) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        try {
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, pkt);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /** Formato propio: [x][z] + las secciones tal cual las escribe vanilla (sin block entities). */
    private static byte[] encodeChunkBlocks(net.minecraft.world.level.chunk.LevelChunk chunk) {
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(chunk.getPos().x);
            buf.writeVarInt(chunk.getPos().z);
            for (var section : chunk.getSections()) {
                section.write(buf);
            }
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    // --- Deduplicación de chunks del plot ---
    // Cada keyframe de la grabación reemite los chunks de la nave, así que al reproducir vuelven a
    // llegar una y otra vez. Reinyectarlos idénticos recarga chunks y luz sin cambiar nada: es la
    // causa de los tirones. Se salta lo ya inyectado mientras el plot siga poblado.
    private static final Set<Long> injectedChunks = ConcurrentHashMap.newKeySet();
    private static volatile Object chunkGenerationLevel;
    private static volatile boolean rebuildPending = false;

    /** Vuelve a permitir la inyección (plot vacío o mundo nuevo tras un salto). */
    private static void resetInjectedChunks() {
        injectedChunks.clear();
        lastSnapshotTick = Integer.MIN_VALUE;
        rebuildPending = true;
    }

    private static boolean alreadyInjected(byte[] data, Minecraft mc) {
        if (chunkGenerationLevel != mc.level) {
            chunkGenerationLevel = mc.level;
            resetInjectedChunks();
        }
        long key = ((long) java.util.Arrays.hashCode(data) << 20) ^ data.length;
        return !injectedChunks.add(key);
    }

    /** Reproducción: re-inyecta el paquete de chunk directamente en el cliente. */
    private static void injectChunkPacket(byte[] data, Minecraft mc) {
        if (alreadyInjected(data, mc)) {
            return;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(data), mc.level.registryAccess(), ConnectionType.NEOFORGE);
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket pkt;
        try {
            pkt = net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
        var connection = mc.getConnection();
        if (connection != null) {
            pkt.handle(connection);   // -> ClientChunkCache: Sable lo enruta al plot
            notePlotChunkArrived();
        }
    }

    /** Reproducción: reconstruye el chunk solo con sus bloques y lo mete en el plot. */
    private static void injectChunkBlocks(byte[] data, Minecraft mc) throws Exception {
        if (alreadyInjected(data, mc)) {
            return;
        }
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            var pos = new net.minecraft.world.level.ChunkPos(buf.readVarInt(), buf.readVarInt());
            var chunk = new net.minecraft.world.level.chunk.LevelChunk(mc.level, pos);
            chunk.replaceWithPacketData(buf, new net.minecraft.nbt.CompoundTag(), tag -> {});
            Object container = plotContainerOf(mc.level);
            if (container == null) {
                return;
            }
            container.getClass()
                    .getMethod("newPopulatedChunk", net.minecraft.world.level.ChunkPos.class,
                            net.minecraft.world.level.chunk.LevelChunk.class)
                    .invoke(container, pos, chunk);
            notePlotChunkArrived();
        } finally {
            buf.release();
        }
    }

    /**
     * Tras poblar el plot hay que re-meshear una vez para que la nave aparezca. Es una recarga
     * completa del renderer (tirón visible), así que se hace UNA sola vez por cada vez que el plot
     * se llena, no en cada keyframe.
     */
    private static void notePlotChunkArrived() {
        if (!rebuildPending) {
            return;
        }
        rebuildPending = false;
        Minecraft.getInstance().execute(() -> {
            try {
                Minecraft.getInstance().levelRenderer.allChanged();
                ReplayGuard.LOGGER.info("[FlashbackFix] Chunks del plot re-inyectados en el cliente: re-meshing "
                        + "para que la nave aparezca.");
            } catch (Throwable ignored) {}
        });
    }

    /**
     * ¿La entidad está dentro de la región de plots (donde viven los bloques y las piezas móviles de
     * las naves)? Se consulta al contenedor de Sable del propio nivel, así que vale igual en el
     * cliente y en el servidor de reproducción.
     */
    public static boolean isInsidePlot(Object entity) {
        try {
            if (!(entity instanceof net.minecraft.world.entity.Entity e)) {
                return false;
            }
            Object container = plotContainerOf(e.level());
            if (container == null) {
                return false;
            }
            return (boolean) container.getClass()
                    .getMethod("inBounds", net.minecraft.world.level.ChunkPos.class)
                    .invoke(container, new net.minecraft.world.level.ChunkPos(e.blockPosition()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object plotContainerOf(Object level) {
        try {
            Class<?> holderIface = Class.forName("dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder");
            if (level == null || !holderIface.isInstance(level)) {
                return null;
            }
            return holderIface.getMethod("sable$getPlotContainer").invoke(level);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasBlocks(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (chunk == null) {
            return false;
        }
        for (var section : chunk.getSections()) {
            if (section != null && !section.hasOnlyAir()) {
                return true;
            }
        }
        return false;
    }

    /** Lado GRABACIÓN: llamado desde el mixin al inicio del handle() de cada paquete de Sable. */
    public static void capture(Object packet) {
        try {
            if (ReplayDetector.isReplayServerActive()) {
                return;   // reproducción: no re-capturar lo que nosotros mismos re-entregamos
            }
            String className = packet.getClass().getName();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            // Mantener el cache de estado inicial SIEMPRE (aunque no se esté grabando todavía).
            if (STOP_TRACKING.equals(className)) {
                Long gone = subLevelIdOf(packet);
                if (gone != null) {
                    for (Map<Long, byte[]> byId : initialState.values()) {
                        byId.remove(gone);
                    }
                }
            } else if (STATE_CLASSES.contains(className)) {
                Long id = subLevelIdOf(packet);
                if (id != null) {
                    initialState.computeIfAbsent(className, k -> new ConcurrentHashMap<>())
                            .put(id, encodePacket(packet, mc));
                }
                if (START_TRACKING.equals(className)) {
                    // Se guarda el UUID aparte: la clave del cache es la coordenada del plot, que no
                    // sirve para saber si un sublevel VIVO ya viaja en el keyframe.
                    for (var component : packet.getClass().getRecordComponents()) {
                        if (component.getType() == java.util.UUID.class) {
                            cachedStartTrackingIds.add((java.util.UUID) component.getAccessor().invoke(packet));
                            break;
                        }
                    }
                }
            }
            if (!ALLOWED.contains(className)) {
                return;
            }
            Object recorder = activeRecorder();
            if (recorder == null) {
                return;   // no se está grabando
            }
            byte[] bytes = encodePacket(packet, mc);
            Packet<?> wrapped = new ClientboundCustomPayloadPacket(new SableBridgePayload(className, bytes));
            if (writePacketAsync == null) {
                writePacketAsync = recorder.getClass().getMethod("writePacketAsync", Packet.class, ConnectionProtocol.class);
            }
            writePacketAsync.invoke(recorder, wrapped, ConnectionProtocol.PLAY);
            if (!loggedCapture) {
                loggedCapture = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Puente Sable GRABANDO: los paquetes de naves/sublevels se "
                        + "estan escribiendo en la grabacion (primer paquete: {}).", className);
            }
        } catch (Throwable t) {
            if (warnedCapture.add(packet.getClass().getName())) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] Puente Sable: fallo capturando {} (se omite esa clase)",
                        packet.getClass().getName(), t);
            }
        }
    }

    /** Lado REPRODUCCIÓN: handler del payload flashbackfix:sable_bridge (hilo principal). */
    public static void replayDispatch(SableBridgePayload payload) {
        if (!ReplayDetector.isReplayServerActive()) {
            return;   // solo tiene sentido dentro de un replay
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            // Chunks del plot (los bloques de la nave) grabados por nosotros en el keyframe.
            if (PLOT_CHUNK_PACKET.equals(payload.classId())) {
                injectChunkPacket(payload.data(), mc);
                return;
            }
            if (PLOT_CHUNK_BLOCKS.equals(payload.classId())) {
                injectChunkBlocks(payload.data(), mc);
                return;
            }
            if (PLOT_ENTITY_ADD.equals(payload.classId()) || PLOT_ENTITY_DATA.equals(payload.classId())) {
                injectEntityPacket(payload.classId(), payload.data(), mc);
                return;
            }
            if (PLOT_ENTITY_SPAWN.equals(payload.classId())) {
                injectEntitySpawnData(payload.data(), mc);
                return;
            }
            if (PLOT_ENTITY_PASSENGERS.equals(payload.classId())) {
                injectPassengers(payload.data(), mc);
                return;
            }
            if (!ALLOWED.contains(payload.classId())) {
                return;
            }
            Class<?> cls = Class.forName(payload.classId());
            StreamCodec<RegistryFriendlyByteBuf, Object> codec = codecOf(cls);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                    Unpooled.wrappedBuffer(payload.data()), mc.level.registryAccess(), ConnectionType.NEOFORGE);
            Object packet;
            try {
                packet = codec.decode(buf);
            } finally {
                buf.release();
            }
            noteSnapshotTick(packet, mc);
            // Preferir handleClient(Level) si el paquete lo expone (p. ej. los snapshots duales).
            for (Method m : cls.getMethods()) {
                if (m.getName().equals("handleClient") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(mc.level.getClass())) {
                    invokeHandle(m, packet, mc.level);
                    logDispatchOnce(payload.classId());
                    afterDispatch(payload.classId(), packet);
                    return;
                }
            }
            // Genérico: handle(<interfaz de contexto de Veil>) con proxy dinámico.
            for (Method m : cls.getMethods()) {
                if (m.getName().equals("handle") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInterface()) {
                    invokeHandle(m, packet, contextProxy(m.getParameterTypes()[0]));
                    logDispatchOnce(payload.classId());
                    afterDispatch(payload.classId(), packet);
                    return;
                }
            }
            if (warnedDispatch.add(payload.classId())) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] Puente Sable: {} no tiene handleClient ni handle(interfaz)",
                        payload.classId());
            }
        } catch (Throwable t) {
            if (warnedDispatch.add(payload.classId())) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] Puente Sable: fallo re-entregando {} en el replay",
                        payload.classId(), t);
            }
        }
    }

    /**
     * Post-entrega. Los BLOQUES de la nave viven en el "plot": una región de chunks lejana que el
     * cliente solo recibe mientras el servidor la streamea a los jugadores que trackean el sublevel.
     * En el replay nadie streamea el plot → nave con colisión (el ReplayServer sí tiene los chunks
     * grabados) pero invisible. Tras entregar el StartTracking (el sublevel y su plot ya existen en
     * el cliente), localizamos el plot y le pedimos al ReplayServer que envíe sus chunks al
     * espectador — el mismo orden que en vivo: tracking primero, chunks del plot después.
     */
    private static void afterDispatch(String classId, Object packet) {
        if (!START_TRACKING.equals(classId)) {
            return;
        }
        try {
            deliverPlotChunks(packet);
        } catch (Throwable t) {
            if (warnedDispatch.add("plot-chunks")) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] Puente Sable: fallo enviando chunks del plot al espectador", t);
            }
        }
    }

    /** uuid del sublevel → última vez que se le enviaron los chunks de su plot. */
    private static final Map<java.util.UUID, Long> lastPlotSend = new ConcurrentHashMap<>();

    /**
     * Re-entregar un StartTracking cuyo sublevel ya existe (cada keyframe/seek lo repite) hace que
     * Sable lance "Plot already exists": no es un error, el trabajo ya estaba hecho.
     */
    private static void invokeHandle(Method m, Object packet, Object arg) throws Exception {
        try {
            m.invoke(packet, arg);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException
                    && String.valueOf(cause.getMessage()).contains("Plot already exists")) {
                return;
            }
            throw e;
        }
    }

    private static void deliverPlotChunks(Object startPacket) throws Exception {
        java.util.UUID uuid = null;
        for (var component : startPacket.getClass().getRecordComponents()) {
            if (component.getType() == java.util.UUID.class) {
                uuid = (java.util.UUID) component.getAccessor().invoke(startPacket);
                break;
            }
        }
        if (uuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastPlotSend.get(uuid);
        if (last != null && now - last < 15_000L) {
            return;   // ya enviados hace poco (los keyframes re-entregan StartTracking en cada seek)
        }
        Minecraft mc = Minecraft.getInstance();
        Object container = plotContainerOf(mc.level);
        if (container == null) {
            return;
        }
        Object plot = null;
        for (Object sub : (java.util.List<?>) container.getClass().getMethod("getAllSubLevels").invoke(container)) {
            if (uuid.equals(sub.getClass().getMethod("getUniqueId").invoke(sub))) {
                plot = sub.getClass().getMethod("getPlot").invoke(sub);
                break;
            }
        }
        if (plot == null) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Puente Sable: StartTracking entregado pero el sublevel {} "
                    + "no aparece en el contenedor del cliente (no se pueden enviar los chunks del plot).", uuid);
            return;
        }
        lastPlotSend.put(uuid, now);

        // Censo en el CLIENTE: ¿ya tiene los bloques de la nave en el plot?
        var holders = (java.util.Collection<?>) plot.getClass().getMethod("getLoadedChunks").invoke(plot);
        int withBlocks = 0;
        for (Object holder : holders) {
            if (hasBlocks((net.minecraft.world.level.chunk.LevelChunk)
                    holder.getClass().getMethod("getChunk").invoke(holder))) {
                withBlocks++;
            }
        }
        ReplayGuard.LOGGER.info("[FlashbackFix] Censo del plot en el CLIENTE (sublevel {}): {} chunk(s) cargados, "
                + "{} con bloques.", uuid, holders.size(), withBlocks);
        if (withBlocks > 0) {
            // El cliente ya tiene la nave: nada que hacer (re-meshear aquí solo causa tirones).
            return;
        } else {
            // Plot vacío: permitir que los chunks del keyframe se re-inyecten y re-meshear al llegar.
            resetInjectedChunks();
            // Normal justo tras el StartTracking: los chunks del plot llegan a continuación, en los
            // payloads que el keyframe trae detrás. NO se piden al mundo del replay: allí esa región
            // se genera como terreno normal y enviarla solo mete relleno falso dentro del plot.
            ReplayGuard.LOGGER.info("[FlashbackFix] El plot aun no tiene bloques en el cliente; deberian llegar "
                    + "en los chunks del keyframe. Si no llegan, la grabacion es anterior a esta version.");
        }
    }

    private static void logDispatchOnce(String classId) {
        if (!loggedDispatch) {
            loggedDispatch = true;
            ReplayGuard.LOGGER.info("[FlashbackFix] Puente Sable REPRODUCIENDO: paquetes de naves/sublevels "
                    + "re-entregados a Sable en el replay (primero: {}).", classId);
        }
        // El re-meshing lo dispara ahora la llegada de los chunks del plot (notePlotChunkArrived),
        // una sola vez por carga: forzarlo también aquí solo añadía recargas completas del renderer.
    }

    /**
     * Proxy dinámico del PacketContext de Veil: responde por tipo de retorno/parámetro. Se invoca
     * ya en el hilo principal, así que cualquier "enqueue" se ejecuta inline.
     */
    private static Object contextProxy(Class<?> iface) {
        Minecraft mc = Minecraft.getInstance();
        InvocationHandler handler = (proxy, method, args) -> {
            Class<?> ret = method.getReturnType();
            // Ejecutar tareas encoladas inline (ya estamos en el hilo principal).
            if (args != null && args.length == 1 && args[0] instanceof Runnable runnable) {
                runnable.run();
                return ret == CompletableFuture.class ? CompletableFuture.completedFuture(null) : null;
            }
            if (ret.isInstance(mc.level)) {
                return mc.level;
            }
            if (mc.player != null && ret.isInstance(mc.player)) {
                return mc.player;
            }
            if (ret.isInstance(mc)) {
                return mc;
            }
            if (ret == boolean.class || ret == Boolean.class) {
                return method.getName().toLowerCase(java.util.Locale.ROOT).contains("client");
            }
            if (ret == int.class) return 0;
            if (ret == long.class) return 0L;
            if (ret == float.class) return 0f;
            if (ret == double.class) return 0d;
            if (ret == CompletableFuture.class) return CompletableFuture.completedFuture(null);
            return null;
        };
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }
}
