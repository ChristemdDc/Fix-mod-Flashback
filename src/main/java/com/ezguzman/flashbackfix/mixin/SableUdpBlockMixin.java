package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fuerza el "modo TCP" de Sable en este cliente bloqueando la activación de su canal UDP.
 *
 * Por qué: los snapshots de movimiento de los sublevels/naves (ClientboundSableSnapshotDualPacket)
 * son de transporte DUAL — viajan por UDP cuando el canal está activo y por TCP si no. Flashback
 * solo graba la conexión TCP: con UDP activo, las naves no quedan en la grabación (invisibles en
 * los replays). Al bloquear aquí el ClientboundSableUDPActivationPacket, el canal UDP nunca se
 * establece y el servidor envía TODO por TCP → tracking y movimiento de naves quedan grabados.
 * También evita que un paquete de activación GRABADO intente abrir UDP contra el servidor real
 * durante una reproducción.
 *
 * Coste: la sincronización de físicas en juego normal usa TCP (latencia ligeramente mayor que
 * UDP). Para restaurar el UDP de Sable, lanzar el juego con -Dflashbackfix.allowSableUdp=true.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.tcp.ClientboundSableUDPActivationPacket", remap = false)
public class SableUdpBlockMixin {

    private static final boolean ALLOW_UDP = Boolean.getBoolean("flashbackfix.allowSableUdp");
    private static boolean flashbackfix$logged = false;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackfix$forceTcpMode(CallbackInfo ci) {
        if (ALLOW_UDP) {
            return;
        }
        if (!flashbackfix$logged) {
            flashbackfix$logged = true;
            ReplayGuard.LOGGER.info("[FlashbackFix] Activacion UDP de Sable BLOQUEADA (modo grabacion TCP): los "
                    + "snapshots de naves/sublevels viajaran por TCP y quedaran grabados en los replays. "
                    + "Para restaurar UDP: -Dflashbackfix.allowSableUdp=true");
        }
        ci.cancel();
    }
}
