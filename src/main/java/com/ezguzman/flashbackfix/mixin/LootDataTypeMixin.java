package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayGuard;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.level.storage.loot.LootDataType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Igual que {@link RecipeManagerMixin} pero para las tablas de botín / datos de datapack que se
 * re-decodifican al abrir el replay. Si un dato modded falla al parsear durante la apertura del
 * replay, se descarta esa entrada en vez de crashear.
 */
@Mixin(LootDataType.class)
public class LootDataTypeMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "deserialize",
            at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;")
    )
    private DataResult flashbackfix$catchDatapackParse(Codec codec, DynamicOps ops, Object input) {
        try {
            return codec.parse(ops, input);
        } catch (RuntimeException | LinkageError e) {
            if (ReplayGuard.isLoadingFlashbackReplay()) {
                ReplayGuard.noteDatapackSkipped(e);
                return DataResult.error(() -> "flashbackfix omitio el dato: " + e);
            }
            throw e;
        }
    }
}
