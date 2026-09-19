package com.ezguzman.flashbackfix.mixin.create;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Las máquinas de Create buscan recetas en su tick vía {@code AllRecipeTypes.find(input, level)},
 * que hace {@code level.getRecipeManager()}. Al reproducir un replay ese Level puede llegar NULO y
 * causa un "Ticking block entity" NPE. Si el Level es nulo devolvemos Optional.empty (sin receta):
 * la máquina no procesa durante el replay, pero Flashback ya muestra el estado grabado.
 *
 * {@code @Pseudo} + {@code require = 0}: si Create no está, el mixin simplemente no se aplica.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.AllRecipeTypes", remap = false)
public class AllRecipeTypesMixin {

    @Inject(
            method = "find(Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void flashbackfix$nullWorldSafe(RecipeInput input, Level level, CallbackInfoReturnable<Optional<?>> cir) {
        if (level == null) {
            ReplayGuard.noteCreateNullWorld();
            cir.setReturnValue(Optional.empty());
        }
    }
}
