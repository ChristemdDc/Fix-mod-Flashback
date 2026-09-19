package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * En un replay, el servidor de reproducción también corre cretania_recipes (lado servidor) y su
 * onLogin envía al espectador un manifest VACÍO (el mundo del replay no tiene contenido vinculado).
 * ClientContentCache.onManifest, correctamente, poda del caché todo lo que no esté en el manifest —
 * lo que en un replay significa BORRAR el caché real del servidor. Aquí bloqueamos el procesamiento
 * de manifests durante replays: el caché de disco (activado por nuestro reload) es la fuente de
 * verdad y nada debe podarlo ni re-descargarlo en una reproducción.
 */
@Pseudo
@Mixin(targets = "com.cretania.recipes.client.content.ClientContentCache", remap = false)
public class CretaniaManifestGuardMixin {

    private static boolean flashbackfix$logged = false;

    @Inject(method = "onManifest", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackfix$ignoreManifestsInReplay(CallbackInfo ci) {
        if (ReplayDetector.isReplayServerActive()) {
            if (!flashbackfix$logged) {
                flashbackfix$logged = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Manifest de contenido dinamico IGNORADO durante el replay "
                        + "(el cache de disco es la fuente de verdad; evita que el manifest vacio del replay "
                        + "pode el cache real).");
            }
            ci.cancel();
        }
    }
}
