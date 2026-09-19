package com.ezguzman.flashbackfix.mixin.create;

import com.ezguzman.flashbackfix.ReplayDetector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create envía ClientboundChainConveyorRidingPacket a todos los clientes cada 10 ticks,
 * incluso en mundos sin bloques de Create (Sinytra/Connector#2075). En el ReplayServer
 * ese tráfico es inútil y era la fuente directa del crash; lo cortamos de raíz.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.chainConveyor.ServerChainConveyorHandler", remap = false)
public class ServerChainConveyorHandlerMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackfix$skipTickInReplay(CallbackInfo ci) {
        if (ReplayDetector.isReplayServerActive()) {
            ci.cancel();
        }
    }
}
