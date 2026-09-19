package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.compat.ReplayVisualsBridge;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuerza los toggles "Render Players"/"Render Entities" de Flashback durante los replays.
 *
 * Flashback los implementa inyectando en {@code EntityRenderDispatcher.shouldRender}, pero otros
 * mods (EntityCulling con su hilo de culling propio) inyectan en el mismo punto y el último en
 * escribir el resultado gana — pisando el "ocultar" de Flashback y dejando el toggle sin efecto.
 * Con priority 4000 este mixin se aplica DESPUÉS de todos ellos y su callback en RETURN (que
 * también cubre los return sintéticos de los cancels previos) tiene la última palabra: si Flashback
 * dice que la entidad no debe verse, no se ve. Fuera de replays, no interviene.
 */
@Mixin(value = EntityRenderDispatcher.class, priority = 4000)
public class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
    private <E extends Entity> void flashbackfix$enforceReplayVisuals(E entity, Frustum frustum, double x, double y,
                                                                      double z, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !ReplayDetector.isReplayServerActive()) {
            return;
        }
        if (!ReplayVisualsBridge.shouldRenderEntity(entity)) {
            cir.setReturnValue(false);
        }
    }
}
