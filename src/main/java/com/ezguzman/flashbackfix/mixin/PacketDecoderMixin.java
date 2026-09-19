package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Al reproducir, los paquetes grabados se vuelven a decodificar en el cliente espectador. Si uno de
 * ellos no se puede leer, netty lanza una DecoderException que TUMBA LA CONEXION: el replay se cierra
 * con "Conexion perdida: Failed to decode packet ...".
 *
 * Pasa con payloads de mods cuyo contenido no se puede reconstruir en el mundo del replay (visto con
 * 'rechiseled:main', que reenvia sus recetas de cincelado). Ninguno de esos paquetes es necesario
 * para VER la grabacion, asi que durante un replay se descarta el paquete ilegible y la reproduccion
 * continua. Fuera de un replay no se toca nada: ahi un fallo de decodificacion si es una senal real.
 */
@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {

    @WrapMethod(method = "decode")
    private void flashbackfix$skipUndecodablePacket(ChannelHandlerContext context, ByteBuf buffer, List<Object> output,
                                                    Operation<Void> original) {
        if (!ReplayDetector.isReplayServerActive()) {
            original.call(context, buffer, output);
            return;
        }
        try {
            original.call(context, buffer, output);
        } catch (Throwable failure) {
            if (buffer.isReadable()) {
                buffer.skipBytes(buffer.readableBytes());
            }
            ReplayGuard.noteUndecodablePacket(failure);
        }
    }
}
