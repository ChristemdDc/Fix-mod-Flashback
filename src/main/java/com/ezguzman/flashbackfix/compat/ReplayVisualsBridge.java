package com.ezguzman.flashbackfix.compat;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Lee los toggles de visuales de Flashback (Render Players / Render Entities) sin dependencia de
 * compilación: EditorStateManager.getCurrent() → EditorState.replayVisuals →
 * ReplayVisuals.renderPlayers / renderEntities (todos públicos, verificados con javap en 0.39.5).
 * Si la reflexión falla o no hay estado, devuelve "renderizar" (no-op).
 */
public final class ReplayVisualsBridge {

    private static boolean initialized = false;
    private static Method getCurrent;
    private static Field replayVisualsField;
    private static Field renderPlayersField;
    private static Field renderEntitiesField;

    private ReplayVisualsBridge() {}

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class<?> manager = Class.forName("com.moulberry.flashback.state.EditorStateManager");
            getCurrent = manager.getMethod("getCurrent");
            Class<?> editorState = Class.forName("com.moulberry.flashback.state.EditorState");
            replayVisualsField = editorState.getField("replayVisuals");
            Class<?> visuals = Class.forName("com.moulberry.flashback.visuals.ReplayVisuals");
            renderPlayersField = visuals.getField("renderPlayers");
            renderEntitiesField = visuals.getField("renderEntities");
        } catch (Throwable t) {
            getCurrent = null;
            ReplayGuard.LOGGER.warn("[FlashbackFix] No se pudo acceder a los visuales de Flashback por reflexion "
                    + "(el refuerzo de Render Players queda inactivo)", t);
        }
    }

    /** true = la entidad debe renderizarse según los toggles de Flashback. */
    public static boolean shouldRenderEntity(Entity entity) {
        init();
        if (getCurrent == null) {
            return true;
        }
        try {
            Object state = getCurrent.invoke(null);
            if (state == null) {
                return true;
            }
            Object visuals = replayVisualsField.get(state);
            if (visuals == null) {
                return true;
            }
            if (entity instanceof Player) {
                return renderPlayersField.getBoolean(visuals);
            }
            return renderEntitiesField.getBoolean(visuals);
        } catch (Throwable t) {
            return true;
        }
    }
}
