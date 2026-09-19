package com.ezguzman.flashbackfix.registrysync;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Arregla los items/bloques modded incorrectos en los replays de Flashback.
 *
 * Causa raíz: Flashback graba los paquetes con los IDs numéricos de registro del SERVIDOR (mientras
 * estás conectado, NeoForge remapea los registros del cliente a esos IDs). Al reproducir, decodifica
 * con los IDs LOCALES (estado "frozen"), que para contenido modded pueden diferir → el ID grabado
 * resuelve a otro item/bloque. El contenido vanilla no se ve afectado porque sus IDs son idénticos.
 *
 * Fix, reutilizando la maquinaria oficial de NeoForge (la misma de la sincronización al conectarte
 * a un servidor):
 *  1) Al entrar a un servidor remoto se captura el mapeo ACTIVO ({@code takeSnapshot(SYNC_TO_CLIENT)})
 *     y se guarda en disco.
 *  2) Al arrancar el ReplayServer de Flashback (antes de decodificar cualquier paquete grabado) se
 *     aplica ese mapeo ({@code applySnapshot}); esto también reconstruye el mapa de blockstates
 *     (BlockCallbacks.onClear/onBake), así que los chunks también decodifican bien.
 *  3) Al cerrarse el ReplayServer se restaura el mapeo local ({@code revertToFrozen()}), igual que
 *     al desconectarte de un servidor.
 */
public final class ReplayRegistryRemapper {

    private static volatile boolean appliedForReplay = false;
    private static volatile boolean attemptedForReplay = false;

    private ReplayRegistryRemapper() {}

    public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        // Entrando al mundo de un REPLAY: activar el contenido dinámico de cretania_recipes desde
        // el caché de disco (su manifest de login nunca llega en una reproducción).
        if (com.ezguzman.flashbackfix.ReplayDetector.isReplayServerActive()) {
            com.ezguzman.flashbackfix.compat.CretaniaContentBridge.triggerReloadIfPresent();
            return;
        }
        // Solo servidores remotos: en singleplayer los IDs activos son los locales y guardarlos
        // pisaría el mapa real del servidor con un mapa identidad.
        if (mc.hasSingleplayerServer()) {
            return;
        }
        String key = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "unknown-server";
        com.ezguzman.flashbackfix.compat.CretaniaContentBridge.rememberServer(key);
        try {
            Map<ResourceLocation, RegistrySnapshot> snapshot = RegistryIdMapStore.captureActiveSnapshots();
            RegistryIdMapStore.save(snapshot, key);
            ReplayGuard.LOGGER.info(
                    "[FlashbackFix] Capturado el mapa de IDs del servidor '{}' ({} registros, captura por byId). "
                            + "Los replays grabados en este servidor se reproduciran con los items/bloques correctos.",
                    key, snapshot.size());
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo capturar el mapa de IDs del servidor", t);
        }
    }

    /**
     * Núcleo síncrono del remapeo: carga el mapa guardado, lo compara con los registros actuales y,
     * si difieren y es seguro, lo aplica EN EL HILO ACTUAL. Ruta principal: el mixin en
     * {@code Flashback.openReplayWorld} (hilo del cliente, antes de que exista servidor o mundo).
     * Idempotente: si ya se aplicó para este replay, no hace nada.
     */
    public static synchronized void applyForReplay(String source) {
        if (appliedForReplay || attemptedForReplay) {
            return;
        }
        attemptedForReplay = true;
        try {
            RegistryIdMapStore.LoadedMap loaded = RegistryIdMapStore.loadLatest();
            Map<ResourceLocation, RegistrySnapshot> stored = loaded == null ? null : loaded.snapshots();
            if (stored == null) {
                ReplayGuard.LOGGER.info(
                        "[FlashbackFix] No hay mapa de IDs guardado todavia; el replay usara los IDs locales. "
                                + "(Entra al servidor al menos una vez con este mod instalado para capturarlo.)");
                return;
            }
            RegistryIdMapStore.Comparison cmp = RegistryIdMapStore.compareToCurrent(stored);
            if (!cmp.isSafeToApply()) {
                ReplayGuard.LOGGER.warn(
                        "[FlashbackFix] NO se aplica el mapa de IDs del servidor: el cliente no tiene {} entrada(s)/"
                                + "{} registro(s) que el servidor si tenia. El replay puede mostrar items/bloques "
                                + "incorrectos. Faltantes (max 10): {}",
                        cmp.missing().size(), cmp.unknownRegistries().size(),
                        cmp.missing().stream().limit(10).map(ResourceLocation::toString).collect(Collectors.joining(", ")));
                return;
            }
            if (cmp.differing() == 0) {
                ReplayGuard.LOGGER.info(
                        "[FlashbackFix] Los IDs locales ya coinciden con los del servidor ({} entradas); "
                                + "no hace falta remapear para este replay.", cmp.totalEntries());
                return;
            }
            // Transaccional: si applySnapshot lanza a mitad de camino dejaria los registros en un
            // estado inconsistente (unos remapeados, otros no, blockstates a medio reconstruir —
            // eso causo el "mundo vacio" de la v1.3.1). Ante CUALQUIER fallo: revertToFrozen().
            Set<ResourceKey<?>> missingNow;
            try {
                missingNow = RegistryManager.applySnapshot(stored, false);
            } catch (Throwable applyError) {
                RegistryManager.revertToFrozen();
                ReplayGuard.LOGGER.warn(
                        "[FlashbackFix] applySnapshot fallo a mitad del remapeo; registros RESTAURADOS al estado "
                                + "local. El replay abre sin remapear (items/bloques modded pueden verse mal).",
                        applyError);
                return;
            }
            if (!missingNow.isEmpty()) {
                RegistryManager.revertToFrozen();
                ReplayGuard.LOGGER.warn(
                        "[FlashbackFix] applySnapshot reporto {} entradas faltantes inesperadas; se restauro el "
                                + "mapeo local. El replay puede mostrar items/bloques incorrectos.",
                        missingNow.size());
                return;
            }
            appliedForReplay = true;
            ReplayGuard.LOGGER.info(
                    "[FlashbackFix] Registros remapeados a los IDs del servidor para el replay "
                            + "({} de {} entradas diferian) [{}]. Items y bloques modded deberian verse correctos.",
                    cmp.differing(), cmp.totalEntries(), source);
            // Verificación: ¿el mapa de blockstates reconstruido coincide con el del servidor?
            if (loaded.signature() != null) {
                String mismatch = RegistryIdMapStore.describeBlockStateMismatch(loaded.signature());
                if (mismatch == null) {
                    ReplayGuard.LOGGER.info(
                            "[FlashbackFix] Mapa de blockstates VERIFICADO: coincide con el del servidor "
                                    + "({} estados). Los bloques del mundo deberian decodificar correctos.",
                            loaded.signature().totalStates());
                } else {
                    ReplayGuard.LOGGER.warn(
                            "[FlashbackFix] Mapa de blockstates NO coincide con el del servidor: {} "
                                    + "Esto explicaria bloques incorrectos/sin textura en el replay (posible "
                                    + "diferencia de version de un mod entre cliente y servidor).", mismatch);
                }
            } else {
                ReplayGuard.LOGGER.info(
                        "[FlashbackFix] Mapa guardado sin firma de blockstates (formato viejo); vuelve a entrar "
                                + "al servidor para regenerarlo con verificacion.");
            }
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Error preparando el mapa de IDs para el replay (se continua sin remapear)", t);
            try {
                RegistryManager.revertToFrozen();
            } catch (Throwable revertError) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] Ademas fallo el revert de seguridad", revertError);
            }
        }
    }

    /**
     * Fallback por si el mixin de {@code openReplayWorld} no aplicó (p. ej. una versión futura de
     * Flashback cambia la firma): ejecuta el remapeo directamente en el hilo del servidor antes de
     * cargar el mundo. En ese punto el cliente está en una pantalla de carga sin mundo, así que la
     * mutación de registros sigue siendo segura. Sin submit/get: nada de esperas entre hilos.
     */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!isReplayServer(event.getServer())) {
            return;
        }
        applyForReplay("fallback ServerAboutToStart, hilo del servidor");
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        if (!isReplayServer(event.getServer())) {
            return;
        }
        attemptedForReplay = false;
        if (appliedForReplay) {
            appliedForReplay = false;
            // Asincrono a proposito: el hilo del cliente puede estar esperando a que el servidor
            // termine de detenerse; un join aqui podria dar deadlock.
            Minecraft.getInstance().execute(() -> {
                try {
                    RegistryManager.revertToFrozen();
                    ReplayGuard.LOGGER.info("[FlashbackFix] Mapeo de IDs local restaurado al cerrar el replay.");
                } catch (Throwable t) {
                    ReplayGuard.LOGGER.warn("[FlashbackFix] Error restaurando el mapeo local tras el replay", t);
                }
            });
        }
    }

    private static boolean isReplayServer(MinecraftServer server) {
        return server != null && server.getClass().getName().startsWith("com.moulberry.flashback.");
    }
}
