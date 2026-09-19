package com.ezguzman.flashbackfix.mixin.flashback;

import com.ezguzman.flashbackfix.registrysync.ReplayRegistryRemapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Aplica el remapeo de IDs de registro EN EL HILO DEL CLIENTE, sincronicamente, justo cuando
 * Flashback empieza a abrir un replay y ANTES de que arranque el ReplayServer.
 *
 * Por qué aquí y no en ServerAboutToStartEvent: ese evento corre en el hilo del servidor mientras
 * el cliente está bloqueado esperando el arranque; en la v1.3.0 el intento de delegar al hilo del
 * cliente con submit(...).get(30s) deadlockeaba, expiraba, y la tarea encolada remapeaba DESPUÉS,
 * en plena carga del mundo (replay vacío + desconexión). Inyectando al inicio de
 * {@code Flashback.openReplayWorld(Path)} (hilo del cliente, aún sin mundo ni servidor) el remapeo
 * es trivialmente seguro. ServerAboutToStartEvent queda solo como fallback directo.
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.Flashback", remap = false)
public class OpenReplayWorldMixin {

    @Inject(method = "openReplayWorld(Ljava/nio/file/Path;)V", at = @At("HEAD"), require = 0, remap = false)
    private static void flashbackfix$remapBeforeReplay(Path path, CallbackInfo ci) {
        ReplayRegistryRemapper.applyForReplay("apertura del replay, hilo del cliente");
    }
}
