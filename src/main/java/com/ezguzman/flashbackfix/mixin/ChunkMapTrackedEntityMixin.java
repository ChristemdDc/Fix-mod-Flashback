package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.ezguzman.flashbackfix.compat.SableBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Dos arreglos del emparejamiento de entidades con el espectador durante los replays.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public class ChunkMapTrackedEntityMixin {

    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Shadow @Final ServerEntity serverEntity;
    @Shadow @Final Entity entity;

    /**
     * Las piezas móviles de una nave (hélices, timones: contraptions de Aeronautics/Create) viven
     * DENTRO del plot del sublevel, a ~20 millones de bloques del mundo normal. En juego normal Sable
     * se encarga de que las vean quienes trackean la nave; en un replay eso no ocurre y el
     * seguimiento vanilla las descarta por distancia: existen en el servidor de reproducción pero
     * nunca llegan al espectador (por eso la cola de la aeronave no aparecía).
     *
     * Durante un replay, a las entidades que están dentro de un plot se les salta la comprobación de
     * distancia y se emparejan siempre con el espectador.
     */
    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackfix$alwaysTrackPlotEntities(ServerPlayer player, CallbackInfo ci) {
        if (!ReplayDetector.isReplayServerActive() || this.entity == player) {
            return;
        }
        if (!SableBridge.isInsidePlot(this.entity)) {
            return;
        }
        if (this.seenBy.add(player.connection)) {
            try {
                this.serverEntity.addPairing(player);
                ReplayGuard.notePlotEntityTracked(this.entity);
            } catch (Throwable t) {
                ReplayGuard.notePairingFailed(this.entity, t);
            }
        }
        ci.cancel();
    }

    /**
     * Red de seguridad genérica: el ReplayServer recrea las entidades sin sus datos custom (esos
     * viajan aparte, en los payloads grabados). Cuando el servidor le "presenta" una de esas
     * entidades al espectador, el mod dueño la re-serializa desde una instancia incompleta y puede
     * lanzar: eso tumbaba el tick del mundo (crash del replay) o el propio spawn del espectador, que
     * quedaba en el vacío con "CRITICAL ERROR: FAILED TO SPAWN PLAYER".
     *
     * Los arreglos concretos (contraptions, planos de Create) siguen siendo preferibles porque
     * conservan la entidad; esto solo evita que CUALQUIER mod no previsto tumbe la reproducción.
     */
    @WrapOperation(
            method = "updatePlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerEntity;addPairing(Lnet/minecraft/server/level/ServerPlayer;)V"),
            require = 0
    )
    private void flashbackfix$pairingSafe(ServerEntity serverEntity, ServerPlayer player, Operation<Void> original) {
        if (!ReplayDetector.isReplayServerActive()) {
            original.call(serverEntity, player);
            return;
        }
        try {
            original.call(serverEntity, player);
        } catch (Throwable t) {
            ReplayGuard.notePairingFailed(serverEntity, t);
        }
    }
}
