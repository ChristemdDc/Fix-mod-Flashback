package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Al reproducir un replay, Flashback re-aplica los datos sincronizados (SynchedEntityData) de las
 * entidades grabadas. Para entidades modded (p. ej. las de Create) el layout de campos puede diferir
 * entre grabación y reproducción bajo Connector: un {@code DataValue} llega con un id/serializer que
 * no corresponde al {@code DataItem} local, y {@code assignValue} revienta el tick del servidor de
 * replay. Aquí filtramos/omitimos esos campos incompatibles en vez de abortar.
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {

    @Shadow
    @Final
    private SynchedEntityData.DataItem<?>[] itemsById;

    @ModifyVariable(method = "assignValues", at = @At("HEAD"), argsOnly = true)
    private List<SynchedEntityData.DataValue<?>> flashbackfix$dropIncompatibleEntityData(
            List<SynchedEntityData.DataValue<?>> values) {
        if (values == null || values.isEmpty()) {
            return values;
        }
        int firstBad = -1;
        for (int i = 0; i < values.size(); i++) {
            if (!flashbackfix$entryFitsEntity(values.get(i))) {
                firstBad = i;
                break;
            }
        }
        if (firstBad < 0) {
            return values;
        }
        if (!ReplayGuard.flashbackInStack()) {
            return values;
        }
        List<SynchedEntityData.DataValue<?>> filtered = new ArrayList<>(values.subList(0, firstBad));
        for (int i = firstBad; i < values.size(); i++) {
            SynchedEntityData.DataValue<?> value = values.get(i);
            if (flashbackfix$entryFitsEntity(value)) {
                filtered.add(value);
            } else {
                ReplayGuard.noteEntityDataDropped(value.id(), this.itemsById.length);
            }
        }
        return filtered;
    }

    @Inject(method = "assignValue", at = @At("HEAD"), cancellable = true)
    private void flashbackfix$tolerateReplayEntityDataMismatch(SynchedEntityData.DataItem<?> item,
                                                               SynchedEntityData.DataValue<?> value,
                                                               CallbackInfo ci) {
        if (item != null
                && !Objects.equals(value.serializer(), item.getAccessor().serializer())
                && ReplayGuard.flashbackInStack()) {
            ReplayGuard.noteEntityDataDropped(item.getAccessor().id(), this.itemsById.length);
            ci.cancel();
        }
    }

    @Unique
    private boolean flashbackfix$entryFitsEntity(SynchedEntityData.DataValue<?> value) {
        int id = value.id();
        if (id < 0 || id >= this.itemsById.length) {
            return false;
        }
        SynchedEntityData.DataItem<?> item = this.itemsById[id];
        if (item == null) {
            return false;
        }
        return Objects.equals(value.serializer(), item.getAccessor().serializer());
    }
}
