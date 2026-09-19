package com.ezguzman.flashbackfix.mixin.create;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * En un replay, el ReplayServer recrea las contraptions de Create desde la grabación, pero la
 * instancia servidor-side queda sin {@code controllerPos} (ese dato viaja en el payload de spawn
 * grabado que se reenvía aparte al espectador). Al "emparejar" la entidad con el espectador, Create
 * serializa el spawn y hace {@code controllerPos.subtract(...)} sobre null → NPE → "Exception
 * ticking world" y crash del replay (pasaba al ver replays con contraptions de rodamiento/pistón y
 * construcciones de Aeronautics).
 *
 * Solo durante un replay, sustituimos el controllerPos nulo por BlockPos.ZERO al serializar: la
 * contraption ya se ve correcta en el replay vía los paquetes grabados; esto solo evita el crash.
 * Fuera de un replay no cambia nada (se delega al original, incluido su NPE como señal de bug real).
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public class ControlledContraptionEntityMixin {

    private static boolean flashbackfix$logged = false;

    @WrapOperation(
            method = "writeAdditional",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;subtract(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;"),
            require = 0
    )
    private BlockPos flashbackfix$nullControllerSafe(BlockPos controllerPos, Vec3i base, Operation<BlockPos> original) {
        if (controllerPos == null && ReplayDetector.isReplayServerActive()) {
            if (!flashbackfix$logged) {
                flashbackfix$logged = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Contraption de Create sin controllerPos en el replay; "
                        + "se serializa con posicion 0 para no crashear (solo avisa una vez).");
            }
            return BlockPos.ZERO;
        }
        return original.call(controllerPos, base);
    }
}
