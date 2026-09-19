package com.ezguzman.flashbackfix;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Detecta si el servidor activo es el ReplayServer de Flashback sin depender de sus
 * clases en tiempo de compilación (Flashback se carga vía Sinytra Connector y puede
 * no estar presente). Los replays de Flashback corren siempre en un servidor integrado
 * local, así que el servidor "actual" es fiable en ambos lados de la conexión.
 */
public final class ReplayDetector {
    private ReplayDetector() {}

    public static boolean isReplayServerActive() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.getClass().getName().startsWith("com.moulberry.flashback.");
    }
}
