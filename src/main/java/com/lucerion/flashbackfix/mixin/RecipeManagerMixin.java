package com.lucerion.flashbackfix.mixin;

import com.lucerion.flashbackfix.FlashbackConnectorFix;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Arregla el TERCER crash al abrir un replay (mismo patron que el de loot, distinto subsistema):
 *
 *   java.lang.ClassCastException: net.minecraft.world.level.material.EmptyFluid cannot be cast to
 *   net.minecraft.core.Holder$Reference
 *
 * Ruta real: RecipeManager.apply -> (ConditionalOps) -> ExtraCodecs$4 -> ClassCastException.
 * Una receta referencia un fluido/item que bajo Connector resuelve al valor por defecto del
 * registro (EmptyFluid / AirItem) y el codec lo castea a Holder.Reference -> revienta.
 *
 * RecipeManager.apply hace:  Recipe.CODEC.parse(ops, json).getOrThrow(JsonParseException::new);
 * Redirigimos esa llamada a parse: si lanza una excepcion mientras se abre un replay, devolvemos
 * DataResult.error(...). Entonces getOrThrow lanza JsonParseException, que el propio vanilla YA
 * captura ("Parsing error loading recipe ...") y la receta se omite. Asi el replay puede abrir.
 *
 * require = 0  -> si en tu version la llamada interna no fuese exactamente Codec.parse, el Mixin
 * simplemente no se aplica (no rompe el arranque); avisame y ajustamos el objetivo.
 */
@Mixin(RecipeManager.class)
public class RecipeManagerMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "apply",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"
            ),
            require = 0
    )
    private DataResult flashbackfix$catchRecipeParse(Codec codec, DynamicOps ops, Object input) {
        try {
            return codec.parse(ops, input);
        } catch (RuntimeException | LinkageError e) {
            if (FlashbackConnectorFix.isLoadingFlashbackReplay()) {
                FlashbackConnectorFix.LOGGER.warn(
                        "[FlashbackConnectorFix] Una receta lanzo {} al abrir el replay; se OMITE.",
                        e.toString());
                return DataResult.error(() -> "FlashbackConnectorFix: receta omitida por excepcion: " + e);
            }
            throw e;
        }
    }
}
