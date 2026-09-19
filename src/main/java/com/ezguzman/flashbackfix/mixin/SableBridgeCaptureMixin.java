package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.compat.SableBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captura de paquetes de Sable para el puente de grabación: al ejecutarse el handle() de cada
 * paquete en el cliente (sea cual sea su transporte — Veil TCP o UDP), si Flashback está grabando
 * se re-serializa dentro de la grabación. El handle original continúa intacto (juego en vivo sin
 * cambios). Ver {@link SableBridge}.
 */
@Pseudo
@Mixin(targets = {
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeBoundsSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopMovingSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundFloatingBlockMaterialPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundPhysicsPropertyPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeSubLevelNamePacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundRecentlySplitSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket",
        "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket"
}, remap = false)
public class SableBridgeCaptureMixin {

    @Inject(method = "handle", at = @At("HEAD"), require = 0)
    private void flashbackfix$captureForRecording(CallbackInfo ci) {
        SableBridge.capture(this);
    }
}
