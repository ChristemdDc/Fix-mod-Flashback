package com.lucerion.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.lucerion.flashbackfix.FlashbackConnectorFix;
import net.minecraft.resources.RegistryDataLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mixin sobre el cargador de registros dinámicos de vanilla.
 *
 * El método central de carga (RegistryDataLoader#load(LoadingFunction, RegistryAccess, List))
 * acumula los errores de cada elemento en un Map y, si NO está vacío, registra los errores
 * y lanza:
 *      throw new IllegalStateException("Failed to load registries due to above errors");
 *
 * Interceptamos la comprobación `map.isEmpty()`. SOLO mientras Flashback está abriendo un
 * replay (FlashbackConnectorFix.isLoadingFlashbackReplay()), forzamos que la comprobación
 * devuelva `true`, de modo que vanilla NO lanza la excepción y devuelve los registros que
 * SÍ se cargaron. Las pocas entradas modded que no se pudieron parsear simplemente quedan
 * fuera del mundo del replay (aceptable para reproducir/editar/exportar).
 *
 * Fuera de Flashback (mundos normales) devolvemos el valor original -> comportamiento vanilla.
 *
 * NOTA sobre el descriptor del método:
 *   Se apunta explícitamente a la sobrecarga "central" que contiene el `map.isEmpty()` y el
 *   throw. Si una futura versión de Minecraft cambiara la firma y el Mixin no encontrara el
 *   objetivo, ajusta el descriptor de `method` (o usa solo "load" + ordinal del isEmpty).
 */
@Mixin(RegistryDataLoader.class)
public class RegistryDataLoaderMixin {

    @ModifyExpressionValue(
            method = "load(Lnet/minecraft/resources/RegistryDataLoader$LoadingFunction;Lnet/minecraft/core/RegistryAccess;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;isEmpty()Z")
    )
    private static boolean flashbackfix$tolerateReplayRegistryErrors(boolean original) {
        // Solo actuamos si Flashback está abriendo el replay (hilo de render, Flashback en la pila).
        if (FlashbackConnectorFix.flashbackInStack()) {
            // Abrimos la ventana de tolerancia para los hilos worker que parsean loot/datapacks DESPUÉS
            // (ReloadableServerRegistries corre en ForkJoinPool, sin Flashback en su pila).
            FlashbackConnectorFix.noteReplayLoading();
            if (!original) { // original == false -> hubo errores -> vanilla lanzaría la excepción
                FlashbackConnectorFix.LOGGER.warn(
                        "[FlashbackConnectorFix] Errores al cargar registros dinámicos del replay: se OMITEN " +
                        "para abrir el replay en vez de crashear. El detalle de qué entradas fallan se imprime " +
                        "justo debajo (RegistryDataLoaderDiagnosticsMixin)."
                );
                return true; // fingimos que no hubo errores -> se omite logErrors()+throw y se devuelven los registros cargados
            }
        }
        return original;
    }
}
