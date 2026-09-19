package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;

/**
 * La otra mitad de {@link PacketDecoderMixin}. El servidor de reproduccion le manda al espectador
 * paquetes reconstruidos desde la grabacion; si alguno referencia contenido que no existe en local
 * (tipico cuando el servidor donde grabaste tiene mods que tu cliente no tiene), la serializacion
 * falla y netty CIERRA la conexion: "Conexion perdida: Failed to encode packet ...".
 *
 * Caso real: 'update_attributes' con el atributo epicfight:stamina cuando la version de Epic Fight
 * del servidor difiere de la del cliente.
 *
 * Durante un replay ese paquete se descarta: se deja el buffer vacio, que es exactamente lo que
 * vanilla ya sabe ignorar al leer (PacketDecoder no hace nada si no hay bytes). Se pierde ese dato
 * concreto —atributos de una entidad, puramente visual para un espectador— en vez del replay entero.
 */
@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {

    @WrapMethod(method = "encode")
    private void flashbackfix$skipUnencodablePacket(ChannelHandlerContext context, Packet<?> packet, ByteBuf buffer,
                                                    Operation<Void> original) {
        if (!ReplayDetector.isReplayServerActive()) {
            original.call(context, packet, buffer);
            return;
        }
        int mark = buffer.writerIndex();
        try {
            original.call(context, packet, buffer);
        } catch (Throwable failure) {
            buffer.writerIndex(mark);
            ReplayGuard.noteUnencodablePacket(packet, failure);
        }
    }
}
