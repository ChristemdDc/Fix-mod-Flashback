package com.ezguzman.flashbackfix;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado compartido para hacer TOLERANTE la apertura de un replay de Flashback bajo
 * Sinytra Connector. Cuando Flashback reconstruye el mundo del replay, vuelve a cargar
 * registros/recetas/loot/config desde cero; bajo Connector, muchas entradas modded no se
 * pueden re-parsear y vanilla aborta toda la carga (crash típico
 * "Failed to load registries..." o ClassCastException a Holder$Reference).
 *
 * En vez de abortar, los mixins de este mod OMITEN las entradas problemáticas. Para saber
 * "estamos abriendo un replay" hay dos señales: (1) Flashback aparece en el stack actual
 * ({@link #flashbackInStack()}), fiable en el hilo que dispara la carga; (2) una ventana de
 * tolerancia temporal ({@link #isLoadingFlashbackReplay()}) que cubre el parseo que ocurre
 * en hilos de fondo (recetas/loot) donde Flashback ya no está en el stack.
 */
public final class ReplayGuard {

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String FLASHBACK_PACKAGE = "com.moulberry.flashback.";
    private static final long WINDOW_MS = 60_000L;

    private static volatile long replayToleranceUntil = 0L;
    private static volatile boolean loggedConfigDefault = false;
    private static volatile boolean loggedEntityDataDrop = false;
    private static volatile boolean loggedCreateNullWorld = false;

    // Contadores para resumir (en vez de spamear una linea por entrada omitida).
    private static final AtomicInteger skippedRecipes = new AtomicInteger();
    private static final AtomicInteger skippedDatapack = new AtomicInteger();
    private static volatile String firstSkipDetail = null;

    private ReplayGuard() {}

    /** ¿Hay alguna clase de Flashback (com.moulberry.flashback.*) en el stack actual? */
    public static boolean flashbackInStack() {
        return StackWalker.getInstance().walk(frames ->
                frames.anyMatch(f -> f.getClassName().startsWith(FLASHBACK_PACKAGE)));
    }

    /** Abre una ventana de tolerancia de 60 s (para el parseo que ocurre fuera del stack de Flashback). */
    public static void noteReplayLoading() {
        replayToleranceUntil = System.currentTimeMillis() + WINDOW_MS;
    }

    public static boolean isLoadingFlashbackReplay() {
        return flashbackInStack() || System.currentTimeMillis() < replayToleranceUntil;
    }

    /** Registra (sin loguear) una receta omitida; guarda el primer error como ejemplo. */
    public static void noteRecipeSkipped(Throwable e) {
        if (skippedRecipes.getAndIncrement() == 0 && firstSkipDetail == null) {
            firstSkipDetail = e.toString();
        }
    }

    /** Registra (sin loguear) un dato de datapack omitido; guarda el primer error como ejemplo. */
    public static void noteDatapackSkipped(Throwable e) {
        if (skippedDatapack.getAndIncrement() == 0 && firstSkipDetail == null) {
            firstSkipDetail = e.toString();
        }
    }

    /** Emite un único resumen de lo omitido y reinicia los contadores. Se llama al terminar la recarga. */
    public static void flushSkipSummary() {
        int recipes = skippedRecipes.getAndSet(0);
        int datapack = skippedDatapack.getAndSet(0);
        if (recipes > 0 || datapack > 0) {
            LOGGER.warn("[FlashbackFix] Al abrir el replay se omitieron {} receta(s) y {} dato(s) de datapack que "
                    + "Connector no puede re-parsear (normal con Create; no afecta la reproduccion). Ejemplo: {}",
                    recipes, datapack, firstSkipDetail);
            firstSkipDetail = null;
        }
    }

    public static void noteConfigDefault() {
        if (!loggedConfigDefault) {
            loggedConfigDefault = true;
            LOGGER.warn("[FlashbackFix] Un mod intento leer un config no cargado (tipico de mods leyendo su "
                    + "config SERVER en cliente/replay). Se devuelve el valor por defecto para evitar el crash "
                    + "'Cannot get config value before config is loaded'. (Solo se avisa una vez por sesion.)");
        }
    }

    public static void noteEntityDataDropped(int fieldId, int definedFields) {
        if (!loggedEntityDataDrop) {
            loggedEntityDataDrop = true;
            LOGGER.warn("[FlashbackFix] Datos de entidad incompatibles al reproducir el replay (campo id={} en una "
                    + "entidad con {} campos definidos): se OMITEN para no crashear. El layout de datos sincronizados "
                    + "de esa entidad modded difiere entre grabacion y reproduccion bajo Connector. (Solo una vez.)",
                    fieldId, definedFields);
        }
    }

    // Entidades cuya data de spawn avanzado YA se aplicó con éxito en este replay: se aplica como
    // máximo una vez por instancia ("el primer éxito gana"). Evita que el payload hueco del
    // re-emparejamiento pise los datos buenos del keyframe, y que re-aplicaciones repetidas
    // corrompan estado global (sable/simulated). WeakHashMap: las instancias descartadas se liberan.
    private static final java.util.Map<Object, Boolean> APPLIED_SPAWN_DATA =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static boolean hasSpawnDataApplied(Object entity) {
        return APPLIED_SPAWN_DATA.containsKey(entity);
    }

    public static void markSpawnDataApplied(Object entity) {
        APPLIED_SPAWN_DATA.put(entity, Boolean.TRUE);
    }

    private static volatile boolean loggedAdvancedSpawnDrop = false;
    private static volatile boolean loggedPayloadDisconnectSuppressed = false;

    /** NeoForge quiso desconectar por un payload sin canal negociado durante el replay; se suprimió. */
    public static void notePayloadDisconnectSuppressed(String reason) {
        if (!loggedPayloadDisconnectSuppressed) {
            loggedPayloadDisconnectSuppressed = true;
            LOGGER.warn("[FlashbackFix] NeoForge intento desconectar por un payload sin canal negociado durante el "
                    + "replay (tipico al buscar en la linea de tiempo: Flashback re-corre la configuracion y el "
                    + "estado de canales se pierde). Se IGNORA el payload en vez de desconectar. Motivo original: {} "
                    + "(solo se avisa una vez por sesion)", reason);
        }
    }

    /** Un payload de spawn avanzado falló al leerse durante el replay y se suprimió la desconexión. */
    public static void noteAdvancedSpawnDropped(String detail) {
        if (!loggedAdvancedSpawnDrop) {
            loggedAdvancedSpawnDrop = true;
            ReplayGuard.LOGGER.warn("[FlashbackFix] Un spawn avanzado de entidad fallo al leerse durante el replay; "
                    + "se IGNORA en vez de desconectar (la entidad ya recibe sus datos reales por los paquetes "
                    + "grabados). Detalle: {} (solo se avisa una vez por sesion)", detail);
        }
    }

    private static final AtomicInteger teamRemovalsSkipped = new AtomicInteger();
    private static volatile boolean loggedTeamRemoval = false;

    /** Paquete de equipo incoherente en el replay: se ignora en vez de dejar que NeoForge haga un informe de crash. */
    public static void noteTeamRemovalSkipped() {
        int total = teamRemovalsSkipped.incrementAndGet();
        if (!loggedTeamRemoval) {
            loggedTeamRemoval = true;
            LOGGER.info("[FlashbackFix] Paquetes de equipos del scoreboard incoherentes durante el replay (tipico de "
                    + "los mods de nametags): se ignoran en vez de lanzar excepcion. Cada una hacia que NeoForge "
                    + "preparase un informe de crash completo y clavaba los FPS. (Solo se avisa una vez; van {}.)",
                    total);
        }
    }

    private static final Set<String> undecodablePackets = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger undecodableCount = new AtomicInteger();

    /**
     * Un paquete grabado no se pudo leer al reproducir. Se descarta en vez de dejar que netty tumbe
     * la conexion del espectador ("Conexion perdida: Failed to decode packet ...").
     */
    public static void noteUndecodablePacket(Throwable error) {
        undecodableCount.incrementAndGet();
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String key = String.valueOf(cause.getMessage());
        if (undecodablePackets.add(key)) {
            LOGGER.warn("[FlashbackFix] Un paquete grabado no se pudo leer al reproducir y se DESCARTA (antes esto "
                    + "cerraba el replay con 'Conexion perdida'). Suele ser un mod cuyo contenido no se puede "
                    + "reconstruir en el mundo del replay. Motivo: {} (un aviso por motivo; van {} descartados)",
                    key, undecodableCount.get());
        }
    }

    private static final Set<String> unencodablePackets = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger unencodableCount = new AtomicInteger();

    /**
     * Un paquete grabado no se pudo re-serializar (tipico si el servidor tiene un mod que tu cliente
     * no tiene). Se descarta ese paquete en vez de cerrar el juego al empezar a grabar.
     */
    public static void noteUnencodablePacket(Object packet, Throwable error) {
        unencodableCount.incrementAndGet();
        String key = packet == null ? "?" : packet.getClass().getSimpleName();
        if (unencodablePackets.add(key)) {
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            LOGGER.warn("[FlashbackFix] Un paquete no se pudo escribir en la grabacion y se OMITE (antes esto "
                    + "cerraba el juego al empezar a grabar). Suele pasar cuando el servidor usa un mod que tu "
                    + "cliente no tiene. Paquete: {} | Motivo: {} (un aviso por tipo de paquete; van {} omitidos)",
                    key, cause.getMessage(), unencodableCount.get());
        }
    }

    private static final Set<String> spawnDataApplied = ConcurrentHashMap.newKeySet();

    /** Diagnóstico: los datos custom (p. ej. la contraption) se aplicaron de verdad en el cliente. */
    public static void noteSpawnDataApplied(Object entity) {
        String key = entity == null ? "?" : entity.getClass().getSimpleName();
        if (spawnDataApplied.add(key)) {
            LOGGER.info("[FlashbackFix] Datos de spawn aplicados en el replay a: {}", key);
        }
    }

    private static volatile boolean loggedInterpolationReset = false;

    /** Se reinició la interpolación de las naves porque el replay retrocedió en el tiempo. */
    public static void noteInterpolationReset() {
        if (!loggedInterpolationReset) {
            loggedInterpolationReset = true;
            LOGGER.info("[FlashbackFix] Retroceso detectado en la linea de tiempo: reiniciada la interpolacion de "
                    + "las naves para que vuelvan a su posicion anterior (antes se quedaban clavadas en la ultima "
                    + "alcanzada). (Solo se avisa una vez.)");
        }
    }

    private static volatile boolean loggedPassengers = false;

    /** Se volvió a montar a quien iba sentado en una pieza de la nave. */
    public static void notePassengersRestored(int riders) {
        if (!loggedPassengers) {
            loggedPassengers = true;
            LOGGER.info("[FlashbackFix] Restaurado quien iba montado en las piezas de la nave ({} pasajero(s) en el "
                    + "primer caso): sin esto los jugadores sentados aparecian de pie. (Solo se avisa una vez.)",
                    riders);
        }
    }

    private static final Set<String> plotRestoreCases = ConcurrentHashMap.newKeySet();

    /** Piezas de la nave recreadas/rellenadas tras un salto en la línea de tiempo. */
    public static void notePlotEntitiesRestored(int recreated, int refilled, int idTaken) {
        String kind = (recreated > 0 ? "R" : "") + (refilled > 0 ? "F" : "") + (idTaken > 0 ? "T" : "");
        if (plotRestoreCases.add(kind)) {
            LOGGER.info("[FlashbackFix] Piezas de nave vigiladas tras saltar en la linea de tiempo: {} recreadas, "
                    + "{} rellenadas (estaban vacias), {} con su hueco ocupado por otra entidad. "
                    + "(Un aviso por tipo de caso.)", recreated, refilled, idTaken);
        }
    }

    private static final AtomicInteger plotEntitiesTracked = new AtomicInteger();
    private static volatile boolean loggedPlotEntity = false;

    /** Una entidad de dentro del plot (hélice, timón...) se fuerza a ser visible para el espectador. */
    public static void notePlotEntityTracked(Object entity) {
        plotEntitiesTracked.incrementAndGet();
        if (!loggedPlotEntity) {
            loggedPlotEntity = true;
            LOGGER.info("[FlashbackFix] Entidades de dentro del plot de una nave (piezas moviles como helices o "
                    + "timones) forzadas a ser visibles para el espectador: el seguimiento vanilla las descartaba "
                    + "por distancia. Primera: {} (solo se avisa una vez).",
                    entity == null ? "?" : entity.getClass().getSimpleName());
        }
    }

    private static final Set<String> pairingFailures = ConcurrentHashMap.newKeySet();

    /**
     * Emparejar una entidad con el espectador falló durante el replay. Se omite esa entidad en vez de
     * tumbar el tick del servidor de reproducción (o el spawn del espectador, que dejaba "FAILED TO
     * SPAWN PLAYER" y la camara en el vacio).
     */
    public static void notePairingFailed(Object entity, Throwable error) {
        String key = entity == null ? "?" : entity.getClass().getName();
        if (pairingFailures.add(key)) {
            LOGGER.warn("[FlashbackFix] No se pudo enviar al espectador los datos de spawn de {} en el replay "
                    + "(la instancia recreada no tiene todos sus datos). Se OMITE esa entidad en vez de crashear. "
                    + "(Una vez por clase.)", key, error);
        }
    }

    public static void noteCreateNullWorld() {
        if (!loggedCreateNullWorld) {
            loggedCreateNullWorld = true;
            LOGGER.warn("[FlashbackFix] Una maquina de Create intento buscar receta con un Level NULO al reproducir "
                    + "el replay; se devuelve 'sin receta' (Optional.empty) para no crashear (era el NPE en "
                    + "AllRecipeTypes.find -> world.getRecipeManager()). La maquina no procesa en el replay, pero "
                    + "Flashback ya muestra el estado grabado. (Solo una vez.)");
        }
    }
}
