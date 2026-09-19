package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayGuard;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Al abrir el replay, la recarga de datos vuelve a decodificar TODAS las recetas. Una receta
 * modded (p. ej. de Create con ingrediente de fluido) puede lanzar durante el parseo bajo
 * Connector (el famoso ClassCastException a Holder$Reference). Envolvemos la llamada
 * {@code Codec.parse} de RecipeManager: si estamos abriendo un replay, capturamos el fallo,
 * lo registramos y devolvemos un DataResult de error (esa receta se descarta) en vez de
 * propagar la excepcion y matar la apertura del replay.
 */
@Mixin(RecipeManager.class)
public class RecipeManagerMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "apply",
            at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;")
    )
    private DataResult flashbackfix$catchRecipeParse(Codec codec, DynamicOps ops, Object input) {
        try {
            return codec.parse(ops, input);
        } catch (RuntimeException | LinkageError e) {
            if (ReplayGuard.isLoadingFlashbackReplay()) {
                ReplayGuard.noteRecipeSkipped(e);
                return DataResult.error(() -> "flashbackfix omitio la receta: " + e);
            }
            throw e;
        }
    }

    /** Al terminar la recarga de recetas, emite un único resumen de lo omitido (en vez de una línea por receta). */
    @Inject(method = "apply", at = @At("RETURN"))
    private void flashbackfix$summarizeRecipeSkips(CallbackInfo ci) {
        ReplayGuard.flushSkipSummary();
    }
}
