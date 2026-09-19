package com.ezguzman.flashbackfix;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Flashback 0.39.5 (para 1.21.1) hace @Shadow/@Inject sobre firmas de
 * HandRenderer que Iris cambió en 1.8.14 (isHandTranslucent(InteractionHand) →
 * isHandTranslucent(ItemStack)), y como su config de mixins es "required", el
 * fallo de aplicación tumba el juego entero.
 *
 * Este handler degrada a WARN los errores de aplicación de los mixins
 * `com.moulberry.flashback.mixin.compat.iris.*` únicamente: el juego continúa
 * sin ese mixin. Único efecto: al fijar la cámara del replay a un jugador en
 * primera persona con shaders, la mano renderizada no refleja los items del
 * jugador espectado. El resto de Flashback e Iris funcionan normal.
 */
public class IrisCompatErrorHandler implements IMixinErrorHandler {

    private static final String FLASHBACK_IRIS_COMPAT_PREFIX = "com.moulberry.flashback.mixin.compat.iris.";

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return suppressIfFlashbackIrisCompat(th, mixin);
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return suppressIfFlashbackIrisCompat(th, mixin);
    }

    private ErrorAction suppressIfFlashbackIrisCompat(Throwable th, IMixinInfo mixin) {
        if (mixin != null && mixin.getClassName() != null
                && mixin.getClassName().startsWith(FLASHBACK_IRIS_COMPAT_PREFIX)) {
            FlashbackFix.LOGGER.warn(
                    "Mixin de Flashback incompatible con esta version de Iris, suprimido para evitar el crash: {} ({})",
                    mixin.getClassName(), th == null ? "sin detalle" : th.getMessage());
            return ErrorAction.WARN;
        }
        return null;
    }
}
