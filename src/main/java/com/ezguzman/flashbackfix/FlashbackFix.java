package com.ezguzman.flashbackfix;

import com.ezguzman.flashbackfix.registrysync.ReplayRegistryRemapper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FlashbackFix.MOD_ID)
public class FlashbackFix {
    public static final String MOD_ID = "flashbackfix";
    public static final Logger LOGGER = LoggerFactory.getLogger("FlashbackFix");

    public FlashbackFix(IEventBus modBus) {
        LOGGER.info("Flashback Fix cargado (Create/Iris/ReplayServer compat)");
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(FMLClientSetupEvent.class, this::onClientSetup);
            // Remapeo de IDs de registro para que los items/bloques modded se vean correctos en replays.
            NeoForge.EVENT_BUS.addListener(ReplayRegistryRemapper::onClientLoggedIn);
            NeoForge.EVENT_BUS.addListener(ReplayRegistryRemapper::onServerAboutToStart);
            NeoForge.EVENT_BUS.addListener(ReplayRegistryRemapper::onServerStopped);
            // Puente Sable: payload que transporta los paquetes de naves/sublevels dentro de la
            // grabación (la red de Veil no pasa por la conexión que Flashback graba).
            // Las piezas de las naves (contraptions dentro del plot) las destruye el replay en cada
            // salto de la línea de tiempo; se re-crean solas desde lo ya recibido.
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class,
                    event -> com.ezguzman.flashbackfix.compat.SableBridge.tickRestorePlotEntities());
            modBus.addListener(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class, event -> {
                event.registrar("1").optional().playToClient(
                        com.ezguzman.flashbackfix.compat.SableBridgePayload.TYPE,
                        com.ezguzman.flashbackfix.compat.SableBridgePayload.STREAM_CODEC,
                        (payload, context) -> com.ezguzman.flashbackfix.compat.SableBridge.replayDispatch(payload));
            });
        }
    }

    /**
     * Iris inicializa {@code HandRenderer} creando buffers de GPU en su bloque estático.
     * Con Flashback presente esa clase puede cargarse desde un worker thread
     * (IrisShaders/Iris#2817) y crashear con "RenderSystem called from wrong thread".
     * Forzamos su carga aquí, en el render thread, para que la inicialización estática
     * ya esté hecha cuando cualquier otro hilo la referencie.
     */
    private void onClientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("iris")) {
            return;
        }
        event.enqueueWork(() -> {
            try {
                Class.forName("net.irisshaders.iris.pathways.HandRenderer");
                LOGGER.info("Iris HandRenderer precargado en el render thread");
            } catch (Throwable t) {
                LOGGER.warn("No se pudo precargar Iris HandRenderer (inofensivo si Iris cambió de estructura)", t);
            }
        });
    }
}
