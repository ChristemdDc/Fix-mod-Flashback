package com.ezguzman.flashbackfix.mixin.flashback;

import com.ezguzman.flashbackfix.compat.SableBridgePayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * El handleCustomPayload del ReplayGamePacketHandler de Flashback lanza UnsupportedPacketException
 * para TODO custom payload (y el llamador lo descarta en silencio) — por eso los payloads del
 * puente Sable grabados jamás llegaban al espectador. Aquí, si el payload es NUESTRO
 * (flashbackfix:sable_bridge), lo reenviamos explícitamente al espectador y cancelamos el throw.
 * Los demás payloads conservan el comportamiento original de Flashback.
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.playback.ReplayGamePacketHandler", remap = false)
public abstract class ReplayCustomPayloadForwardMixin {

    @Shadow(remap = false)
    public abstract void forward(Packet<?> packet);

    private static final java.util.Set<String> flashbackfix$seen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackfix$forwardBridgePayloads(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        // Diagnóstico: registrar (una vez por id) qué payloads atraviesan este punto en el replay.
        String id = String.valueOf(packet.payload().type().id());
        if (flashbackfix$seen.add(id)) {
            com.ezguzman.flashbackfix.ReplayGuard.LOGGER.info(
                    "[FlashbackFix] handleCustomPayload del replay recibio payload '{}' (clase {})",
                    id, packet.payload().getClass().getSimpleName());
        }
        if (packet.payload() instanceof SableBridgePayload) {
            this.forward(packet);
            ci.cancel();
        }
    }
}
