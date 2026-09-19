package com.ezguzman.flashbackfix.mixin;

import com.ezguzman.flashbackfix.ReplayDetector;
import com.ezguzman.flashbackfix.ReplayGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rendimiento: en los replays, los paquetes de equipos grabados (los usan los mods de nametags para
 * el orden y color de cada jugador) llegan desordenados respecto al scoreboard reconstruido, y
 * vanilla lanza "Player is either on another team or not on any team" al quitar a alguien de un
 * equipo en el que ya no está. No es fatal, pero NeoForge PREPARA UN INFORME DE CRASH COMPLETO por
 * cada excepción de paquete: se midieron 804 en cinco minutos de reproducción, y eso es lo que
 * clavaba los FPS a 0 de golpe.
 *
 * Durante un replay, quitar a un jugador de un equipo al que no pertenece pasa a ser una operación
 * sin efecto. Fuera de un replay no cambia nada.
 */
@Mixin(ClientPacketListener.class)
public class ClientTeamPacketMixin {

    @WrapOperation(
            method = "handleSetPlayerTeamPacket",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Scoreboard;removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V"),
            require = 0
    )
    private void flashbackfix$lenientTeamRemoval(Scoreboard scoreboard, String player, PlayerTeam team,
                                                 Operation<Void> original) {
        if (!ReplayDetector.isReplayServerActive()) {
            original.call(scoreboard, player, team);
            return;
        }
        if (team != null && !team.getPlayers().contains(player)) {
            ReplayGuard.noteTeamRemovalSkipped();
            return;
        }
        try {
            original.call(scoreboard, player, team);
        } catch (IllegalStateException e) {
            ReplayGuard.noteTeamRemovalSkipped();
        }
    }
}
