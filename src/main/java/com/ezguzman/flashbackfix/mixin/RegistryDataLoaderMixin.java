package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.resources.RegistryDataLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code RegistryDataLoader.load} acumula en un mapa los errores de cada entrada de registro
 * y, si el mapa no está vacío, lanza "Failed to load registries due to above errors". Al abrir
 * un replay bajo Connector eso mata la apertura. Aquí, si Flashback está cargando, fingimos que
 * el mapa está vacío (isEmpty -> true) para NO lanzar: las entradas modded que fallaron
 * simplemente se omiten y el replay abre. También abrimos la ventana de tolerancia para que el
 * parseo de recetas/loot en hilos de fondo también sea tolerante.
 */
@Mixin(RegistryDataLoader.class)
public class RegistryDataLoaderMixin {

    @ModifyExpressionValue(
            method = "load(Lnet/minecraft/resources/RegistryDataLoader$LoadingFunction;Lnet/minecraft/core/RegistryAccess;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;isEmpty()Z")
    )
    private static boolean flashbackfix$tolerateReplayRegistryErrors(boolean original) {
        if (ReplayGuard.flashbackInStack()) {
            ReplayGuard.noteReplayLoading();
            if (!original) {
                ReplayGuard.LOGGER.warn("[FlashbackFix] Errores al cargar registros dinamicos del replay: se OMITEN "
                        + "para abrir el replay en vez de crashear. El detalle de que entradas fallan se imprime "
                        + "justo debajo.");
                return true;
            }
        }
        return original;
    }
}
