package com.ezguzman.flashbackfix.mixin.flashback;

import com.ezguzman.flashbackfix.compat.ComplexSpawnSnapshotWriter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Se engancha al hook vacío {@code Recorder.writeCustomSnapshot(Consumer)} de Flashback (previsto
 * para que otros mods añadan paquetes al snapshot) y escribe los payloads de spawn avanzado de las
 * entidades complejas. Ver {@link ComplexSpawnSnapshotWriter}.
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.record.Recorder", remap = false)
public class RecorderCustomSnapshotMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "writeCustomSnapshot", at = @At("HEAD"), require = 0, remap = false)
    private void flashbackfix$writeComplexSpawns(Consumer consumer, CallbackInfo ci) {
        ComplexSpawnSnapshotWriter.append(consumer);
    }
}
