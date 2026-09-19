package com.ezguzman.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * En un replay, Flashback mantiene el control de cámara con el mouse mientras su editor (ImGui) está
 * abierto, pero NO comprueba si además hay un menú vanilla abierto (menú de pausa, opciones, etc.).
 * Resultado: al abrir el Game Menu, mover el mouse por el menú también gira la cámara y arruina la
 * toma.
 *
 * El giro de la cámara del replay pasa por {@code MouseHandler.turnPlayer} -> {@code LocalPlayer.turn}.
 * Aquí forzamos ese giro a 0 cuando hay un MENÚ real abierto ({@code screen.isPauseScreen()} == true:
 * menú de pausa, opciones, etc.), igual que Flashback hace con sus flags flightLockYaw/Pitch.
 *
 * Importante: se filtra por {@code isPauseScreen()} y NO por "hay cualquier pantalla". Al entrar a un
 * replay hay una pantalla transitoria de carga ({@code ReceivingLevelScreen}, cuyo isPauseScreen es
 * false); si congeláramos con cualquier pantalla, la cámara quedaría bloqueada al entrar hasta togglear
 * F1. El editor de Flashback es ImGui (screen == null), así que tampoco se ve afectado.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @WrapOperation(
            method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V")
    )
    private void flashbackfix$freezeCameraWhenMenuOpen(LocalPlayer player, double dx, double dy, Operation<Void> original) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null && screen.isPauseScreen()) {
            original.call(player, 0.0, 0.0);
        } else {
            original.call(player, dx, dy);
        }
    }
}
