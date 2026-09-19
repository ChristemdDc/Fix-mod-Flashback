package com.ezguzman.flashbackfix.mixin.flashback;

import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Al GRABAR, Flashback vuelve a serializar cada paquete recibido para escribirlo en el archivo. Si el
 * servidor usa un mod que tu cliente no tiene, algunos paquetes traen entradas de registro que no
 * existen en local y la serializacion lanza EncoderException — en el hilo del guardador, donde nadie
 * la recoge: el juego se cierra nada mas empezar a grabar.
 *
 * Caso real: servidor con Epic Fight y cliente sin el ⇒ "Failed to encode packet
 * clientbound/minecraft:update_attributes / Can't find id for epicfight:stamina".
 *
 * Aqui se descarta ESE paquete en vez de tumbar el juego: se retrocede el buffer a como estaba (para
 * no dejar datos a medias que corrompan la grabacion) y se escribe en su lugar un relleno inofensivo
 * que mantiene la estructura del archivo intacta. El resto de la grabacion continua con normalidad;
 * solo se pierde ese paquete concreto.
 *
 * El relleno es un ClientboundProjectilePowerPacket sobre un id de entidad inexistente: Flashback SI
 * lo soporta al reproducir (verificado en su bytecode: los paquetes no soportados lanzan
 * UnsupportedPacketException y matan el bucle del servidor de reproduccion) y no tiene ningun efecto
 * ni en el servidor de reproduccion ni en el cliente.
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.io.AsyncReplaySaver", remap = false)
public class AsyncReplaySaverMixin {

    @WrapOperation(
            method = "lambda$writeGamePackets$2",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V",
                    ordinal = 2
            ),
            require = 0
    )
    private void flashbackfix$skipUnencodablePacket(StreamCodec<?, ?> codec, Object buffer, Object packet,
                                                    Operation<Void> original) {
        int mark = buffer instanceof ByteBuf buf ? buf.writerIndex() : -1;
        try {
            original.call(codec, buffer, packet);
        } catch (Throwable failure) {
            if (mark < 0) {
                throw failure;   // sin buffer no se puede deshacer nada: mejor el fallo original
            }
            ((ByteBuf) buffer).writerIndex(mark);
            try {
                original.call(codec, buffer, new ClientboundProjectilePowerPacket(Integer.MAX_VALUE, 0.0D));
            } catch (Throwable ignored) {
                ((ByteBuf) buffer).writerIndex(mark);
            }
            ReplayGuard.noteUnencodablePacket(packet, failure);
        }
    }
}
