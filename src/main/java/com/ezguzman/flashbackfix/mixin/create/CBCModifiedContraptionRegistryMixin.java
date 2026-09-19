package com.ezguzman.flashbackfix.mixin.create;

import com.ezguzman.flashbackfix.ReplayDetector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Big Cannons intercepta TODO addFreshEntity para comprobar si la entidad es una
 * "contraption frágil" (mecánica de carga de cañones) llamando a
 * {@code isFragileContraption(contraption)}. Al buscar en la línea de tiempo de un replay,
 * Flashback re-genera las contraptions con instancia hueca (contraption == null) y esa comprobación
 * hace NPE → Flashback registra "Unable to spawn entity" y la máquina desaparece/queda congelada
 * tras el seek.
 *
 * Durante un replay la mecánica de cañones no aplica: devolvemos false directamente (la entidad se
 * genera y el espectador recibe sus datos reales por los paquetes grabados). Fuera de un replay no
 * se toca nada.
 */
@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.cannon_loading.CBCModifiedContraptionRegistry", remap = false)
public class CBCModifiedContraptionRegistryMixin {

    @Inject(method = "isFragileContraption", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackfix$skipFragileCheckInReplay(CallbackInfoReturnable<Boolean> cir) {
        if (ReplayDetector.isReplayServerActive()) {
            cir.setReturnValue(false);
        }
    }
}
