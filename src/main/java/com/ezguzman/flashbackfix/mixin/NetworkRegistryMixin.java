package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Las conexiones del ReplayServer de Flashback (viewer y fake players) nunca negocian
 * canales de red de NeoForge, así que cualquier mod que envíe un payload durante un
 * replay (Create chain conveyors, Jade, etc.) muere con
 * "Payload ... may not be sent to the client!" (Sinytra/Connector#2075, #1457).
 * Dentro de un replay omitimos esa validación: el paquete sigue su curso normal y la
 * conexión falsa lo entrega o lo descarta sin reventar el servidor.
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public class NetworkRegistryMixin {

    @Inject(
            method = "checkPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void flashbackfix$allowClientboundInReplay(Packet<?> packet, ServerCommonPacketListener listener, CallbackInfo ci) {
        if (ReplayDetector.isReplayServerActive()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "checkPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void flashbackfix$allowServerboundInReplay(Packet<?> packet, ClientCommonPacketListener listener, CallbackInfo ci) {
        if (ReplayDetector.isReplayServerActive()) {
            ci.cancel();
        }
    }

    /**
     * Entrega directa del puente Sable: cuando el payload reenviado por el ReplayServer es nuestro
     * flashbackfix:sable_bridge, lo procesamos aquí mismo (encolado al hilo principal) y cancelamos
     * el resto del método — sin depender de canales negociados ni registros de la conexión.
     */
    @Inject(
            method = "handleModdedPayload(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void flashbackfix$deliverSableBridge(ClientCommonPacketListener listener,
                                                        net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet,
                                                        CallbackInfo ci) {
        if (packet.payload() instanceof com.ezguzman.flashbackfix.compat.SableBridgePayload bridge
                && ReplayDetector.isReplayServerActive()) {
            net.minecraft.client.Minecraft.getInstance().execute(
                    () -> com.ezguzman.flashbackfix.compat.SableBridge.replayDispatch(bridge));
            ci.cancel();
        }
    }

    /**
     * Al buscar en la línea de tiempo, Flashback re-corre la fase de configuración del espectador
     * con su versión sintetizada y el estado de canales negociados se pierde; el siguiente payload
     * modded recibido (p. ej. neoforge:network grabado) dispara "Incompatible client! ... (No
     * Channel for ...)" y expulsa del replay. Durante un replay suprimimos esa desconexión: el
     * payload problemático simplemente se descarta (el método hace return tras el disconnect).
     */
    @WrapOperation(
            method = "handleModdedPayload(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;disconnect(Lnet/minecraft/network/chat/Component;)V"),
            require = 0
    )
    private static void flashbackfix$noClientDisconnectInReplay(Connection connection, Component reason, Operation<Void> original) {
        if (ReplayDetector.isReplayServerActive()) {
            ReplayGuard.notePayloadDisconnectSuppressed(String.valueOf(reason));
            return;
        }
        original.call(connection, reason);
    }

    /** Variante serverbound del guard anterior (payloads del espectador hacia el ReplayServer). */
    @WrapOperation(
            method = "handleModdedPayload(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;disconnect(Lnet/minecraft/network/chat/Component;)V"),
            require = 0
    )
    private static void flashbackfix$noServerDisconnectInReplay(ServerCommonPacketListener listener, Component reason, Operation<Void> original) {
        if (ReplayDetector.isReplayServerActive()) {
            ReplayGuard.notePayloadDisconnectSuppressed(String.valueOf(reason));
            return;
        }
        original.call(listener, reason);
    }
}
