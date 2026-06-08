package com.lucerion.flashbackfix.mixin;

import com.lucerion.flashbackfix.FlashbackConnectorFix;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.level.storage.loot.LootDataType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Arregla el SEGUNDO crash al abrir un replay:
 *
 *   java.lang.ClassCastException: net.minecraft.world.item.AirItem cannot be cast to
 *   net.minecraft.core.Holder$Reference
 *
 * Ruta real (del crash report): ReloadableServerRegistries -> LootDataType.deserialize ->
 * (condicion "fingerprint" de Bookshelf) -> ExtraCodecs -> ClassCastException.
 *
 * Ese cast es una excepcion NO controlada lanzada DENTRO de un codec, por lo que escapa al
 * manejo normal de errores de vanilla (que solo captura DataResult.error) y revienta toda la
 * carga del mundo del replay.
 *
 * LootDataType.deserialize hace basicamente:  DataResult result = this.codec.parse(ops, data);
 * Aqui redirigimos esa llamada a codec.parse: si lanza una excepcion mientras se abre un
 * replay, la registramos y devolvemos DataResult.error(...) en vez de propagarla. Asi
 * deserialize devuelve Optional.empty() y ese elemento (loot table / predicado / modifier) se
 * omite, permitiendo que el replay se abra. Fuera de Flashback, se relanza la excepcion.
 *
 * Se usa @Redirect (de Mixin base) en lugar de @WrapMethod porque la version de MixinExtras
 * incluida en NeoForge 21.1.230 no trae @WrapMethod.
 */
@Mixin(LootDataType.class)
public class LootDataTypeMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "deserialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"
            )
    )
    private DataResult flashbackfix$catchDatapackParse(Codec codec, DynamicOps ops, Object input) {
        try {
            return codec.parse(ops, input);
        } catch (RuntimeException | LinkageError e) {
            if (FlashbackConnectorFix.isLoadingFlashbackReplay()) {
                FlashbackConnectorFix.LOGGER.warn(
                        "[FlashbackConnectorFix] Un dato de datapack lanzo {} al abrir el replay; se OMITE.",
                        e.toString());
                return DataResult.error(() -> "FlashbackConnectorFix: elemento omitido por excepcion: " + e);
            }
            throw e;
        }
    }
}
