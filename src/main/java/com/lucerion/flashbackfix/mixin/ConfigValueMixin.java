package com.lucerion.flashbackfix.mixin;

import com.lucerion.flashbackfix.FlashbackConnectorFix;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Arregla el crash:
 *   java.lang.IllegalStateException: Cannot get config value before config is loaded
 *
 * Causa: algunos mods (tipico de los hechos con MCreator, p.ej. protection_pixel) leen un config
 * de tipo SERVER en codigo que tambien corre en el CLIENTE (manejadores de PlayerTickEvent, etc.).
 * Al conectarte a un servidor dedicado, el cliente NO carga los configs SERVER, asi que cuando ese
 * codigo cliente hace config.get() -> ModConfigSpec.ConfigValue.getRaw() revienta con la excepcion
 * de arriba durante el tick de entidades (Level.guardEntityTick) y crashea el juego.
 *
 * Solucion: interceptamos getRaw(). SOLO cuando el config aun NO esta cargado, devolvemos el valor
 * por defecto (defaultSupplier) en vez de lanzar la excepcion. Cuando el config SI esta cargado
 * (caso normal), no hacemos nada y se comporta exactamente como vanilla.
 *
 * Es decir: lecturas normales = identicas a vanilla; lectura de un config no cargado = valor por
 * defecto en lugar de crash. Esto cubre protection_pixel y cualquier otro mod con el mismo patron.
 *
 * Estructura confirmada en NeoForge 21.1.230:
 *   ConfigValue tiene los campos 'spec' (ModConfigSpec) y 'defaultSupplier' (Supplier),
 *   y getRaw() hace checkState(spec.loadedConfig != null, "Cannot get config value before config is loaded").
 */
@Mixin(ModConfigSpec.ConfigValue.class)
public abstract class ConfigValueMixin {

    @Shadow
    private ModConfigSpec spec;

    @SuppressWarnings("rawtypes")
    @Shadow
    private Supplier defaultSupplier;

    @Inject(method = "getRaw()Ljava/lang/Object;", at = @At("HEAD"), cancellable = true)
    private void flashbackfix$defaultWhenConfigNotLoaded(CallbackInfoReturnable<Object> cir) {
        ModConfigSpec s = this.spec;
        // spec == null -> "spec not built"; !isLoaded() -> "config not loaded".
        // En ambos casos, devolver el valor por defecto evita el crash.
        if (s == null || !((ModConfigSpecAccessor) (Object) s).flashbackfix$isLoaded()) {
            FlashbackConnectorFix.noteConfigDefault();
            cir.setReturnValue(this.defaultSupplier.get());
        }
    }
}
