package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.handlers.ClientPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Durante un replay, el ReplayServer re-empareja entidades complejas (contraptions de Create,
 * sublevels de Sable/Aeronautics) desde instancias recreadas SIN sus datos internos; el payload de
 * spawn avanzado resultante puede fallar al leerse en el cliente (p. ej. NPE "contraption is null"
 * al activar una Rock Cutting Wheel) y NeoForge responde DESCONECTANDO al espectador del replay.
 *
 * Aquí suprimimos esa desconexión únicamente durante replays: el fallo se registra y se ignora.
 * No se pierde nada — la entidad recibe sus datos reales mediante los paquetes grabados que
 * Flashback reenvía. Fuera de un replay el disconnect original se mantiene intacto.
 */
@Mixin(value = ClientPayloadHandler.class, remap = false)
public class ClientPayloadHandlerMixin {

    @WrapOperation(
            method = "handle(Lnet/neoforged/neoforge/network/payload/AdvancedAddEntityPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/handling/IPayloadContext;disconnect(Lnet/minecraft/network/chat/Component;)V"),
            require = 0
    )
    private static void flashbackfix$noDisconnectInReplay(IPayloadContext context, Component reason, Operation<Void> original) {
        if (ReplayDetector.isReplayServerActive()) {
            ReplayGuard.noteAdvancedSpawnDropped(String.valueOf(reason));
            return;
        }
        original.call(context, reason);
    }

    /**
     * Durante un replay, los datos de spawn avanzado se aplican COMO MÁXIMO UNA VEZ por instancia
     * de entidad ("el primer éxito gana"). Motivo: para la misma entidad pueden llegar dos
     * payloads — el bueno del keyframe enriquecido y el hueco del re-emparejamiento del
     * ReplayServer — en cualquier orden. Sin este guard, el hueco podía pisar los datos buenos
     * (máquina invisible al retroceder) o una re-aplicación repetida corromper estado global de
     * mods como sable/simulated (cabeza fija, cámara bloqueada). Solo se marca tras un read
     * EXITOSO: si el hueco llega primero y falla, el bueno posterior aplica normalmente.
     */
    @WrapOperation(
            method = "handle(Lnet/neoforged/neoforge/network/payload/AdvancedAddEntityPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/entity/IEntityWithComplexSpawn;readSpawnData(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V"),
            require = 0
    )
    private static void flashbackfix$applySpawnDataOnce(IEntityWithComplexSpawn entity, RegistryFriendlyByteBuf buf, Operation<Void> original) {
        if (!ReplayDetector.isReplayServerActive()) {
            original.call(entity, buf);
            return;
        }
        if (ReplayGuard.hasSpawnDataApplied(entity)) {
            return;
        }
        original.call(entity, buf);
        ReplayGuard.markSpawnDataApplied(entity);
        ReplayGuard.noteSpawnDataApplied(entity);
    }
}
