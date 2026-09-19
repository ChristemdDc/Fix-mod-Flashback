package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CargaDinamica (mod de infraestructura del servidor del usuario) suprime payloads clientbound
 * cuyos canales no están declarados para el servidor actual. Tras un salto en la línea de tiempo
 * de un replay, re-evalúa el contexto, no reconoce el ReplayServer y empieza a suprimir paquetes
 * vitales (create:server_speed → kinetics estáticas; sync de Sable → cabeza/cuerpo congelados...).
 *
 * Durante un replay, {@code shouldSuppress} devuelve false siempre: en una reproducción local no
 * hay nada que "proteger" y todos los paquetes grabados deben fluir. En servidores reales el
 * comportamiento de CargaDinamica queda intacto.
 */
@Pseudo
@Mixin(targets = "com.cargadinamica.compat.ClientboundPayloadSuppressor", remap = false)
public class CargaDinamicaSuppressorMixin {

    @Inject(method = "shouldSuppress", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackfix$neverSuppressInReplay(CallbackInfoReturnable<Boolean> cir) {
        if (ReplayDetector.isReplayServerActive()) {
            cir.setReturnValue(false);
        }
    }
}
