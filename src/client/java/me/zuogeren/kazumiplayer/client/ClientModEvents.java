package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public class ClientModEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ENTITY.get(),
                VideoScreenRenderer::new);
    }
}
