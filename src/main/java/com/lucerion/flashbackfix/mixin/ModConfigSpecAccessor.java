package com.lucerion.flashbackfix.mixin;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor para llamar al metodo package-private ModConfigSpec.isLoaded()
 * desde nuestro Mixin de ConfigValue (que esta en otro paquete).
 */
@Mixin(ModConfigSpec.class)
public interface ModConfigSpecAccessor {

    @Invoker("isLoaded")
    boolean flashbackfix$isLoaded();
}
