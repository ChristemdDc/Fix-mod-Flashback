package com.lucerion.flashbackfix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Flashback Connector Fix
 * -----------------------
 * Arreglo de compatibilidad para usar el mod Flashback (Fabric) dentro de NeoForge
 * mediante Sinytra Connector, en un pack pesado (Create + addons, etc.).
 *
 * Al abrir un replay, Flashback reconstruye un "mundo de replay" y recarga datos del juego.
 * En un pack grande bajo Connector eso revienta en dos sitios distintos:
 *
 *   1) Registros dinámicos (RegistryDataLoader): vanilla aborta todo si UNA entrada modded
 *      no parsea  ->  "Failed to load registries due to above errors".
 *   2) Loot tables / datapacks (ReloadableServerRegistries -> LootDataType): un códec
 *      (p. ej. la condición "fingerprint" de Bookshelf) lanza un ClassCastException no
 *      controlado (AirItem -> Holder.Reference) que escapa y crashea.
 *
 * Este mod hace TOLERANTES esas dos cargas SOLO cuando se está abriendo un replay: omite las
 * entradas que fallan y continúa, en vez de crashear. Fuera de Flashback no cambia nada.
 *
 * Detección entre hilos: la carga de registros corre en el hilo de render (con Flashback en
 * la pila), pero el parse de loot corre en hilos worker (ForkJoinPool) donde Flashback NO
 * está en la pila. Por eso, cuando detectamos en el hilo de render que se abre un replay,
 * abrimos una "ventana de tolerancia" temporal que también cubre a esos hilos worker.
 */
@Mod(FlashbackConnectorFix.MODID)
public class FlashbackConnectorFix {

    public static final String MODID = "flashbackfix";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String FLASHBACK_PACKAGE = "com.moulberry.flashback.";

    /** Momento (epoch ms) hasta el cual toleramos fallos en CUALQUIER hilo (ventana de replay). */
    private static volatile long replayToleranceUntil = 0L;
    /** Duración de la ventana de tolerancia desde la última señal de carga de replay. */
    private static final long WINDOW_MS = 60_000L;

    public FlashbackConnectorFix() {
        LOGGER.info("[FlashbackConnectorFix] Cargado (build 5: registros + loot + recetas en replays, y configs no cargadas -> valor por defecto).");
    }

    /** ¿Hay un frame de Flashback en la pila del hilo ACTUAL? (válido en el hilo que invoca Flashback). */
    public static boolean flashbackInStack() {
        return StackWalker.getInstance().walk(frames ->
                frames.anyMatch(f -> f.getClassName().startsWith(FLASHBACK_PACKAGE)));
    }

    /** Abre/renueva la ventana de tolerancia: "se está cargando un replay ahora mismo". */
    public static void noteReplayLoading() {
        replayToleranceUntil = System.currentTimeMillis() + WINDOW_MS;
    }

    /**
     * True si estamos abriendo un replay: bien porque Flashback está en la pila (hilo de
     * render), bien porque estamos dentro de la ventana de tolerancia (hilos worker).
     */
    public static boolean isLoadingFlashbackReplay() {
        return flashbackInStack() || System.currentTimeMillis() < replayToleranceUntil;
    }

    /** Avisa (una sola vez) que se devolvió un valor de config por defecto por no estar cargado. */
    private static volatile boolean loggedConfigDefault = false;

    public static void noteConfigDefault() {
        if (!loggedConfigDefault) {
            loggedConfigDefault = true;
            LOGGER.warn("[FlashbackConnectorFix] Un mod intentó leer un config no cargado (típico de mods MCreator " +
                    "leyendo su config SERVER en el cliente multiplayer). Se devuelve el valor por defecto para evitar " +
                    "el crash 'Cannot get config value before config is loaded'. (Solo se avisa una vez por sesión.)");
        }
    }
}
