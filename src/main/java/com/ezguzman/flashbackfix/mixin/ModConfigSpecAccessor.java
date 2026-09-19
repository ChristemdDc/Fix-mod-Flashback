package com.ezguzman.flashbackfix.mixin;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Acceso al método package-private {@code ModConfigSpec.isLoaded()} para {@link ConfigValueMixin}. */
@Mixin(ModConfigSpec.class)
public interface ModConfigSpecAccessor {

    @Invoker("isLoaded")
    boolean flashbackfix$isLoaded();
}
