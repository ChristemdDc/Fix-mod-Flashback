package com.ezguzman.flashbackfix.mixin.flashback;

import com.ezguzman.flashbackfix.compat.ReplayTeamApplier;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * El handler de reproducción de Flashback solo reenvía (forward) los paquetes de equipo del
 * scoreboard al espectador; los del snapshot inicial se pierden si la conexión aún no está lista
 * (prefijos [ADMIN] etc. ausentes). Este mixin además los aplica al scoreboard del ReplayServer,
 * que vanilla sincroniza completo al espectador al entrar. El forward original se mantiene.
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.playback.ReplayGamePacketHandler", remap = false)
public class ReplayGamePacketHandlerMixin {

    @Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"), require = 0, remap = false)
    private void flashbackfix$applyTeamToReplayScoreboard(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
        ReplayTeamApplier.apply(packet);
    }

    /**
     * Flashback considera "no soportado" el keep-alive y lanza una excepcion que mata el bucle del
     * servidor de reproduccion: la grabacion no se puede abrir. Un keep-alive no significa nada al
     * reproducir, asi que se ignora. (Tambien permite abrir grabaciones donde este paquete acabo
     * escrito como relleno.)
     */
    @Inject(method = "handleKeepAlive", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void flashbackfix$ignoreKeepAlive(ClientboundKeepAlivePacket packet, CallbackInfo ci) {
        ci.cancel();
    }
}
