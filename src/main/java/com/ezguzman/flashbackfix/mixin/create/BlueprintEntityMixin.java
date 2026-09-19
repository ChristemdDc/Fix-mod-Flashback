package com.ezguzman.flashbackfix.mixin.create;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mismo patrón que {@link ControlledContraptionEntityMixin}: el ReplayServer recrea los planos
 * (blueprints) de Create sin sus datos, así que {@code direction}/{@code verticalOrientation} quedan
 * nulos. Al emparejar la entidad con el espectador, Create los serializa y hace
 * {@code get3DDataValue()} sobre null → NPE. Eso reventaba dos veces: al ENTRAR al replay (mataba
 * placeNewPlayer → "CRITICAL ERROR: FAILED TO SPAWN PLAYER" y el espectador aparecía en el vacío) y
 * más tarde en el tick del mundo (crash del servidor de reproducción).
 *
 * Durante un replay se serializa con la orientación por defecto (0). Fuera de un replay se delega al
 * original, conservando el NPE como señal de un bug real.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.equipment.blueprint.BlueprintEntity", remap = false)
public class BlueprintEntityMixin {

    private static boolean flashbackfix$logged = false;

    @WrapOperation(
            method = "addAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;get3DDataValue()I"),
            require = 0
    )
    private int flashbackfix$nullDirectionSafe(Direction direction, Operation<Integer> original) {
        if (direction == null && ReplayDetector.isReplayServerActive()) {
            if (!flashbackfix$logged) {
                flashbackfix$logged = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Plano (blueprint) de Create sin orientacion en el replay; "
                        + "se serializa con la orientacion por defecto para no crashear (solo avisa una vez).");
            }
            return 0;
        }
        return original.call(direction);
    }
}
