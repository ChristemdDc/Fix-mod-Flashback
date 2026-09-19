package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.compat.CretaniaContentBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Durante un replay, ClientContentCache.serverId() de cretania_recipes resolvería a
 * "local_<mundo>" (el ReplayServer es un servidor integrado) y el caché de contenido dinámico
 * apuntaría a una carpeta vacía. Lo redirigimos al id del último servidor real conectado, donde
 * vive el contenido descargado. Ver {@link CretaniaContentBridge}.
 */
@Pseudo
@Mixin(targets = "com.cretania.recipes.client.content.ClientContentCache", remap = false)
public class CretaniaContentCacheMixin {

    @Inject(method = "serverId", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackfix$replayServerId(CallbackInfoReturnable<String> cir) {
        if (ReplayDetector.isReplayServerActive()) {
            String id = CretaniaContentBridge.replayContentServerId();
            if (id != null) {
                cir.setReturnValue(id);
            }
        }
    }
}
