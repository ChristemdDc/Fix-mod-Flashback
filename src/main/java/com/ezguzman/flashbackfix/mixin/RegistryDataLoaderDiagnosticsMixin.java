package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/**
 * Diagnóstico: imprime EXACTAMENTE qué entradas de registro fallaron y se omitieron al abrir el
 * replay (para poder investigar qué mod las causa). No cambia el comportamiento; solo lee el mapa
 * de errores local de {@code RegistryDataLoader.load} y lo registra.
 */
@Mixin(RegistryDataLoader.class)
public class RegistryDataLoaderDiagnosticsMixin {

    @ModifyExpressionValue(
            method = "load(Lnet/minecraft/resources/RegistryDataLoader$LoadingFunction;Lnet/minecraft/core/RegistryAccess;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;isEmpty()Z")
    )
    private static boolean flashbackfix$logFailingReplayRegistries(boolean original,
                                                                   @Local Map<ResourceKey<?>, Exception> errors) {
        if (ReplayGuard.isLoadingFlashbackReplay() && errors != null && !errors.isEmpty()) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] === {} entrada(s) de registro fallaron al abrir el replay (se omiten) ===",
                    errors.size());
            errors.forEach((key, ex) -> ReplayGuard.LOGGER.warn("[FlashbackFix]   - {}  ->  {}", key, String.valueOf(ex)));
            ReplayGuard.LOGGER.warn("[FlashbackFix] === fin de la lista de registros omitidos ===");
        }
        return original;
    }
}
