package com.lucerion.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.lucerion.flashbackfix.FlashbackConnectorFix;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/**
 * DIAGNÓSTICO (no cambia el comportamiento del juego).
 *
 * Cuando Flashback abre un replay y hay entradas de registro que no se pueden cargar, este
 * Mixin imprime en el log EXACTAMENTE qué entradas fallan y por qué. Eso permite identificar
 * el mod/registro culpable, que suele ser también el origen del ClassCastException que ocurre
 * justo después al construir el mundo del replay.
 *
 * Es deliberadamente "best-effort": `require = 0`. Si en alguna versión de Minecraft el Mixin
 * no pudiera capturar el mapa de errores, simplemente NO se aplica y el arreglo principal
 * (RegistryDataLoaderMixin) sigue funcionando igual.
 */
@Mixin(RegistryDataLoader.class)
public class RegistryDataLoaderDiagnosticsMixin {

    @ModifyExpressionValue(
            method = "load(Lnet/minecraft/resources/RegistryDataLoader$LoadingFunction;Lnet/minecraft/core/RegistryAccess;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;isEmpty()Z"),
            require = 0
    )
    private static boolean flashbackfix$logFailingReplayRegistries(boolean original,
                                                                   @Local(ordinal = 0) Map<ResourceKey<?>, Exception> errors) {
        if (FlashbackConnectorFix.isLoadingFlashbackReplay() && errors != null && !errors.isEmpty()) {
            FlashbackConnectorFix.LOGGER.warn(
                    "[FlashbackConnectorFix] === {} entrada(s) de registro fallaron al abrir el replay (se omiten) ===",
                    errors.size());
            errors.forEach((key, ex) ->
                    FlashbackConnectorFix.LOGGER.warn("[FlashbackConnectorFix]   - {}  ->  {}", key, String.valueOf(ex)));
            FlashbackConnectorFix.LOGGER.warn(
                    "[FlashbackConnectorFix] === fin de la lista de registros omitidos ===");
        }
        return original; // NO modifica el valor; solo registra (el arreglo lo hace el otro Mixin)
    }
}
