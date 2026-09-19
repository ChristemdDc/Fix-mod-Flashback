package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Físicas de Sable en MODO INERTE durante replays (versión granular del fix v1.5.2, que cancelaba
 * el inicializador completo del contenedor y rompía el sistema de TRACKING que un listener de
 * Sable exige — NPE "trackingSystem is null" al cargar el mundo del replay).
 *
 * Ahora el inicializador corre entero (tracking, observers, tickets — todo lo que el resto de
 * Sable espera, y que además necesitaremos para ver las naves), pero el sistema de físicas queda
 * dormido: se salta su initialize() (la llamada nativa a Rapier que mataba la JVM —
 * hs_err_pid5296) y todos sus puntos de entrada de simulación. En una reproducción no se simula
 * nada: el movimiento viene de los paquetes grabados.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem", remap = false)
public class SablePhysicsSystemGuardMixin {

    private static boolean flashbackfix$logged = false;

    private static boolean flashbackfix$inReplay() {
        return ReplayDetector.isReplayServerActive();
    }

    @Inject(method = "initialize", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackfix$skipNativeInit(CallbackInfo ci) {
        if (flashbackfix$inReplay()) {
            if (!flashbackfix$logged) {
                flashbackfix$logged = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Fisicas de Sable en modo INERTE para el replay "
                        + "(sin init nativo de Rapier ni simulacion; el tracking queda intacto).");
            }
            ci.cancel();
        }
    }

    @Inject(method = {"tick", "onSubLevelAdded", "onSubLevelRemoved", "handleBlockChange",
            "wakeUpObjectsAt", "updateMassDataFromBlockChange", "updatePose", "onConfigUpdated"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackfix$dormantInReplay(CallbackInfo ci) {
        if (flashbackfix$inReplay()) {
            ci.cancel();
        }
    }

    @Inject(method = {"tryPunch", "recoverSubLevel"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackfix$noopBooleansInReplay(CallbackInfoReturnable<Boolean> cir) {
        if (flashbackfix$inReplay()) {
            cir.setReturnValue(false);
        }
    }
}
