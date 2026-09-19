package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.neoforged.neoforge.network.filters.GenericPacketSplitter;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * NeoForge serializa cada paquete ANTES que el codificador de vanilla, en su divisor de paquetes
 * grandes, para saber si hay que trocearlo. Por eso un paquete imposible de serializar revienta aqui
 * y no en {@link PacketEncoderMixin}: la excepcion sale antes y cierra la conexion del espectador
 * ("Conexion perdida: Failed to encode packet 'clientbound/minecraft:update_attributes'").
 *
 * Durante un replay ese paquete se sustituye por uno inofensivo. No vale con dejar la salida vacia:
 * netty exige que este punto produzca al menos un mensaje ("GenericPacketSplitter must produce at
 * least one message"). El sustituto es un ClientboundProjectilePowerPacket sobre un id de entidad
 * inexistente, que el cliente ignora por completo. Fuera de un replay no se toca nada.
 */
@Mixin(value = GenericPacketSplitter.class, remap = false)
public class GenericPacketSplitterMixin {

    @WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Ljava/util/List;)V")
    private void flashbackfix$skipUnencodablePacket(ChannelHandlerContext context, Packet<?> packet,
                                                    List<Object> output, Operation<Void> original) throws Exception {
        if (!ReplayDetector.isReplayServerActive()) {
            original.call(context, packet, output);
            return;
        }
        int size = output.size();
        try {
            original.call(context, packet, output);
        } catch (Throwable failure) {
            while (output.size() > size) {
                output.remove(output.size() - 1);
            }
            output.add(new ClientboundProjectilePowerPacket(Integer.MAX_VALUE, 0.0D));
            ReplayGuard.noteUnencodablePacket(packet, failure);
        }
    }
}
