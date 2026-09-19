package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Muchos mods (incl. Create y mods MCreator) leen su config al tickear. Al reproducir un replay,
 * el spec de config puede no estar cargado y {@code ConfigValue.get()} lanza
 * "Cannot get config value before config is loaded". Si el spec no está cargado, devolvemos el
 * valor por defecto en vez de crashear.
 */
@Mixin(ModConfigSpec.ConfigValue.class)
public abstract class ConfigValueMixin {

    @Shadow(remap = false)
    private ModConfigSpec spec;

    @Shadow(remap = false)
    @org.spongepowered.asm.mixin.Final
    private Supplier<?> defaultSupplier;

    @Inject(method = "get", at = @At("HEAD"), cancellable = true, remap = false)
    private void flashbackfix$defaultWhenConfigNotLoaded(CallbackInfoReturnable<Object> cir) {
        if (this.spec == null || !((ModConfigSpecAccessor) this.spec).flashbackfix$isLoaded()) {
            ReplayGuard.noteConfigDefault();
            cir.setReturnValue(this.defaultSupplier.get());
        }
    }
}
