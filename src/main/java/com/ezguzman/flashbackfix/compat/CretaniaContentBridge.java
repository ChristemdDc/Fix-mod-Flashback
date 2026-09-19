package com.ezguzman.flashbackfix.compat;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

/**
 * Puente para que el contenido dinámico de cretania_recipes (items/bloques creados en el servidor)
 * se vea dentro de los replays.
 *
 * Cómo funciona su mod: los items dinámicos son items base + componente CUSTOM_DATA con un slug —
 * es decir, los datos YA quedan grabados en el replay. El visual (modelos/texturas) vive en un
 * caché de disco por servidor (cretania_recipes_cache/<serverId>/) y se activa con
 * DynamicItemsClient.reloadFromCache(), que normalmente dispara el manifest recibido al hacer
 * login al servidor. En un replay ese manifest nunca llega y además serverId() resolvería a
 * "local_<mundo>" (caché vacío). Este puente arregla ambas cosas:
 *  1) Al conectarte a un servidor se guarda su id de contenido (mismo algoritmo de saneado).
 *  2) Durante un replay, un mixin redirige ClientContentCache.serverId() a ese id guardado.
 *  3) Al entrar al mundo del replay se invoca reloadFromCache() por reflexión (si el mod está).
 */
public final class CretaniaContentBridge {

    private static final String FILE = "last_server_content_id.txt";
    private static volatile String cachedId = null;
    private static boolean loggedReload = false;

    private CretaniaContentBridge() {}

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("flashbackfix").resolve(FILE);
    }

    /** Mismo saneado que usa cretania_recipes: minúsculas; [a-z0-9] se conserva, el resto '_'. */
    private static String sanitizeLikeCretania(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '_');
        }
        return sb.toString();
    }

    /** Llamar al conectarse a un servidor remoto: recuerda su id de contenido. */
    public static void rememberServer(String rawIp) {
        try {
            String id = sanitizeLikeCretania(rawIp);
            cachedId = id;
            Files.createDirectories(file().getParent());
            Files.writeString(file(), id);
        } catch (Throwable t) {
            ReplayGuard.LOGGER.debug("[FlashbackFix] No se pudo guardar el id de contenido del servidor", t);
        }
    }

    /**
     * Id de contenido a usar durante un replay: el guardado, o como fallback el directorio de
     * cretania_recipes_cache modificado más recientemente que no sea local_*/
    public static String replayContentServerId() {
        if (cachedId != null) {
            return cachedId;
        }
        try {
            if (Files.isRegularFile(file())) {
                String id = Files.readString(file()).trim();
                if (!id.isBlank()) {
                    cachedId = id;
                    return id;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Path base = FMLPaths.GAMEDIR.get().resolve("cretania_recipes_cache");
            if (Files.isDirectory(base)) {
                try (var dirs = Files.list(base)) {
                    var best = dirs.filter(Files::isDirectory)
                            .filter(d -> {
                                String n = d.getFileName().toString();
                                return !n.startsWith("local_") && !n.equals("unknown");
                            })
                            .max(Comparator.comparingLong(d -> d.toFile().lastModified()));
                    if (best.isPresent()) {
                        cachedId = best.get().getFileName().toString();
                        return cachedId;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Al entrar al mundo del replay: activar el contenido dinámico desde el caché de disco. */
    public static void triggerReloadIfPresent() {
        try {
            Class<?> cls = Class.forName("com.cretania.recipes.client.DynamicItemsClient");
            cls.getMethod("reloadFromCache").invoke(null);
            if (!loggedReload) {
                loggedReload = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Contenido dinamico de cretania_recipes recargado desde el "
                        + "cache (id '{}') para el replay: items/bloques del servidor deberian verse.",
                        replayContentServerId());
            }
        } catch (ClassNotFoundException absent) {
            // cretania_recipes no está instalado: nada que hacer.
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo recargar el contenido dinamico de cretania_recipes", t);
        }
    }
}
