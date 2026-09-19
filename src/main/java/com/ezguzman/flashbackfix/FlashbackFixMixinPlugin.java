package com.ezguzman.flashbackfix;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Se ejecuta durante la inicialización de la config de mixins (mucho antes de que
 * cargue cualquier clase del juego). Registra: (1) el manejador de errores que suprime
 * los mixins de compat de Flashback incompatibles con la versión de Iris instalada, y
 * (2) el MixinCanceller que desactiva la optimización cache_strongholds de ModernFix
 * (incompatible con el ReplayServer de Flashback).
 */
public class FlashbackFixMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        Mixins.registerErrorHandlerClass("com.ezguzman.flashbackfix.IrisCompatErrorHandler");
        registerModernFixCanceller();
    }

    /**
     * Usa MixinSquared (lo incluye Flashback como jar anidado) para cancelar la mixin de ModernFix
     * que crashea al abrir replays. En try/catch: si MixinSquared no está, el mod sigue funcionando
     * sin este guard.
     */
    private void registerModernFixCanceller() {
        try {
            com.bawnorton.mixinsquared.canceller.MixinCancellerRegistrar.register(
                    new com.ezguzman.flashbackfix.compat.ModernFixReplayCanceller());
            ReplayGuard.LOGGER.info("[FlashbackFix] Canceller de ModernFix cache_strongholds registrado (compat replay).");
        } catch (Throwable t) {
            ReplayGuard.LOGGER.info("[FlashbackFix] MixinSquared no disponible; se omite el guard de ModernFix ({}).",
                    t.toString());
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
