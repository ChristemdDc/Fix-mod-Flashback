package com.ezguzman.flashbackfix.compat;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;

import java.util.function.Consumer;

/**
 * Enriquece cada snapshot/keyframe de Flashback con los payloads de spawn avanzado de NeoForge
 * ({@link AdvancedAddEntityPayload}) de todas las entidades complejas visibles.
 *
 * Por qué: el snapshot de Flashback (mod Fabric) recrea las entidades de forma sintética SIN los
 * datos custom de NeoForge. Para una contraption de Create (o un ship/sublevel), eso significa que
 * si un salto en la línea de tiempo aterriza en un keyframe posterior a su aparición, la entidad se
 * regenera VACÍA y queda invisible (solo se veía bien si el salto reproducía el payload original
 * grabado). Escribiendo aquí, en cada keyframe, el payload real de cada entidad (capturado del
 * estado del cliente durante la grabación, donde los datos están completos), cualquier salto
 * aterriza en un snapshot con la información necesaria y la entidad se reconstruye entera.
 *
 * Se invoca desde el hook vacío {@code Recorder.writeCustomSnapshot}, previsto por Flashback
 * exactamente para que otros mods añadan paquetes al snapshot.
 */
public final class ComplexSpawnSnapshotWriter {

    private static boolean loggedOnce = false;
    private static final java.util.Set<String> loggedExcluded = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ComplexSpawnSnapshotWriter() {}

    /**
     * Se incluyen TODAS las entidades complejas: la aplicación en el cliente es "como máximo una
     * vez por instancia" (ver ClientPayloadHandlerMixin), así que re-entregar el payload en cada
     * keyframe es seguro (la corrupción por doble aplicación de la v1.4.0 ya no puede ocurrir).
     * Cada clase incluida se registra una vez para diagnóstico.
     */
    public static void append(Consumer<Packet<? super ClientGamePacketListener>> consumer) {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            int written = 0;
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof IEntityWithComplexSpawn) {
                    try {
                        // Envuelto en NUESTRO canal: Flashback descarta los payloads normales al
                        // reproducir, asi que enviados "a pelo" nunca llegaban y las contraptions se
                        // reconstruian vacias (invisibles).
                        consumer.accept(SableBridge.wrapSpawnData(entity));
                        written++;
                        if (loggedExcluded.add(entity.getClass().getName())) {
                            ReplayGuard.LOGGER.info("[FlashbackFix] Clase compleja incluida en keyframes: {}",
                                    entity.getClass().getName());
                        }
                    } catch (Throwable perEntity) {
                        ReplayGuard.LOGGER.debug("[FlashbackFix] No se pudo serializar el spawn complejo de {} (se omite)",
                                entity.getType(), perEntity);
                    }
                }
            }
            if (written > 0 && !loggedOnce) {
                loggedOnce = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Snapshot de grabacion enriquecido con {} spawn(s) "
                        + "complejo(s). (Solo se avisa una vez por sesion.)", written);
            }
            // Estado inicial de naves/sublevels de Sable ya trackeados (naves pre-ensambladas):
            // sin esto, el replay recibe snapshots de una nave que nunca fue creada.
            SableBridge.appendInitialStates(consumer);
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Error enriqueciendo el snapshot con spawns complejos", t);
        }
    }
}
