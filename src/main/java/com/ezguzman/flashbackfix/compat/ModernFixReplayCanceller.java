package com.ezguzman.flashbackfix.compat;

import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.List;

/**
 * Cancela (en el cliente) la optimización {@code perf.cache_strongholds} de ModernFix.
 *
 * Al abrir un replay, {@code ReplayServer.loadLevel} construye un {@code ServerLevel} cuyo generador
 * de chunks es nulo. El mixin {@code cache_strongholds.ServerLevelMixin} de ModernFix intenta llamar
 * {@code IChunkGenerator.mfix$setStrongholdCachePath(...)} sobre ese generador nulo y lanza un NPE
 * que mata el ReplayServer ("Cannot invoke ... because instance is null").
 *
 * Como nuestro mod es solo de cliente, el servidor dedicado conserva ModernFix intacto (allí el
 * generador no es nulo y la optimización funciona). El coste de desactivar el cache de strongholds
 * en el cliente es despreciable.
 *
 * Se registra vía MixinSquared, que Flashback incluye como jar anidado (siempre disponible con
 * Flashback presente). El registro va en {@code FlashbackFixMixinPlugin.onLoad}, envuelto en
 * try/catch por si MixinSquared no estuviera.
 */
public class ModernFixReplayCanceller implements MixinCanceller {

    private static final String TARGET = ".perf.cache_strongholds.";

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        return mixinClassName != null
                && mixinClassName.contains("modernfix")
                && mixinClassName.contains(TARGET);
    }
}
