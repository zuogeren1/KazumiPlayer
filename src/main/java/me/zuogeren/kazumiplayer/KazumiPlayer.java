package me.zuogeren.kazumiplayer;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.client.ClientModEvents;
import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(KazumiPlayer.MODID)
public class KazumiPlayer {
    public static final String MODID = "kazumiplayer";
    private static final Logger LOGGER = LogUtils.getLogger();

    public KazumiPlayer(IEventBus modEventBus, ModContainer modContainer, Dist dist) {
        LOGGER.info("KazumiPlayer initializing...");

        // Register blocks, items, and block entity types (both sides)
        VideoScreenRegistration.register(modEventBus);

        // Register client-side renderers
        if (dist.isClient()) {
            modEventBus.register(ClientModEvents.class);
        }

        LOGGER.info("KazumiPlayer initialized on {}", dist);
    }
}
