package com.ezguzman.flashbackfix.compat;

import com.ezguzman.flashbackfix.ReplayGuard;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Aplica los paquetes de equipo del scoreboard (prefijos/sufijos/colores de jugadores) al
 * scoreboard del ReplayServer de Flashback.
 *
 * Por qué: el handler de reproducción de Flashback solo hace {@code forward(packet)} hacia el
 * espectador, sin tocar el scoreboard del servidor de replay. Los paquetes del snapshot inicial se
 * reenvían antes de que la conexión del espectador esté lista y se pierden — resultado: los
 * prefijos ([ADMIN], etc.) solo aparecen si el equipo se actualizó durante la grabación. Al
 * aplicarlos aquí en el {@link ServerScoreboard} (cuyas mutaciones se difunden solas a los
 * jugadores conectados, y que vanilla envía completo al espectador cuando entra), los prefijos
 * quedan siempre visibles. La lógica replica el manejo vanilla del cliente para este paquete.
 */
public final class ReplayTeamApplier {

    private static boolean loggedOnce = false;

    private ReplayTeamApplier() {}

    public static void apply(ClientboundSetPlayerTeamPacket packet) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.getClass().getName().startsWith("com.moulberry.flashback.")) {
                return;
            }
            ServerScoreboard scoreboard = server.getScoreboard();

            ClientboundSetPlayerTeamPacket.Action teamAction = packet.getTeamAction();
            PlayerTeam team = scoreboard.getPlayerTeam(packet.getName());
            if (teamAction == ClientboundSetPlayerTeamPacket.Action.ADD && team == null) {
                team = scoreboard.addPlayerTeam(packet.getName());
            }
            if (team == null) {
                return;
            }
            PlayerTeam playerTeam = team;

            packet.getParameters().ifPresent(params -> {
                playerTeam.setDisplayName(params.getDisplayName());
                playerTeam.setColor(params.getColor());
                playerTeam.unpackOptions(params.getOptions());
                Team.Visibility visibility = Team.Visibility.byName(params.getNametagVisibility());
                if (visibility != null) {
                    playerTeam.setNameTagVisibility(visibility);
                }
                Team.CollisionRule collision = Team.CollisionRule.byName(params.getCollisionRule());
                if (collision != null) {
                    playerTeam.setCollisionRule(collision);
                }
                playerTeam.setPlayerPrefix(params.getPlayerPrefix());
                playerTeam.setPlayerSuffix(params.getPlayerSuffix());
            });

            ClientboundSetPlayerTeamPacket.Action playerAction = packet.getPlayerAction();
            if (playerAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
                for (String player : packet.getPlayers()) {
                    scoreboard.addPlayerToTeam(player, playerTeam);
                }
            } else if (playerAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                for (String player : packet.getPlayers()) {
                    scoreboard.removePlayerFromTeam(player, playerTeam);
                }
            }

            if (teamAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                scoreboard.removePlayerTeam(playerTeam);
            }

            if (!loggedOnce) {
                loggedOnce = true;
                ReplayGuard.LOGGER.info("[FlashbackFix] Equipos del scoreboard aplicados al ReplayServer "
                        + "(prefijos/colores de jugadores visibles en el replay).");
            }
        } catch (Throwable t) {
            ReplayGuard.LOGGER.warn("[FlashbackFix] Error aplicando paquete de equipo al replay (se ignora)", t);
        }
    }
}
