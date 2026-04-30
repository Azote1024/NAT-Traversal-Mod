package com.azote.nat_traversal_mod;

import com.azote.nat_traversal_mod.net.SupabaseRoomsPublisher;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Nat_traversal_mod.MODID)
public class Nat_traversal_mod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "nat_traversal_mod";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final long PUBLISH_INTERVAL_SECONDS = 60L;
    private ScheduledExecutorService roomPublisherScheduler;

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Nat_traversal_mod(ModContainer modContainer) {
        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        LOGGER.info("[nat-traversal-mod] Initialized.");
    }

    private void onServerStarted(ServerStartedEvent event) {
        int serverPort = event.getServer().getPort();
        LOGGER.info("[nat-traversal-mod] Server started on port {}. Publish room now and every {}s.", serverPort, PUBLISH_INTERVAL_SECONDS);

        SupabaseRoomsPublisher.publishOpenRoom(serverPort);
        startPeriodicPublish(serverPort);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        stopPeriodicPublish();
        SupabaseRoomsPublisher.closeRoomAsync();
    }

    private void startPeriodicPublish(int serverPort) {
        stopPeriodicPublish();
        roomPublisherScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "nat-traversal-room-publisher");
            thread.setDaemon(true);
            return thread;
        });

        roomPublisherScheduler.scheduleAtFixedRate(
                () -> SupabaseRoomsPublisher.publishOpenRoom(serverPort),
                PUBLISH_INTERVAL_SECONDS,
                PUBLISH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void stopPeriodicPublish() {
        if (roomPublisherScheduler == null) {
            return;
        }

        roomPublisherScheduler.shutdownNow();
        roomPublisherScheduler = null;
    }
}
