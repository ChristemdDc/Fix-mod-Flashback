package com.ezguzman.flashbackfix.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Envoltorio grabable de un paquete de Sable: la red de Sable (API de Veil) usa su propio
 * transporte que Flashback no ve, así que al grabar re-serializamos cada paquete de Sable con su
 * CODEC público y lo escribimos en la grabación dentro de este payload. En reproducción, el
 * payload viaja por el forward normal del replay y {@link SableBridge#replayDispatch} reconstruye
 * y re-entrega el paquete original.
 */
public record SableBridgePayload(String classId, byte[] data) implements CustomPacketPayload {

    public static final Type<SableBridgePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("flashbackfix", "sable_bridge"));

    public static final StreamCodec<FriendlyByteBuf, SableBridgePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SableBridgePayload::classId,
            ByteBufCodecs.BYTE_ARRAY, SableBridgePayload::data,
            SableBridgePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
