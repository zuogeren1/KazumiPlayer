package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染器注册（仅挂 mod 事件总线）。
 * 快捷键注册与轮询见 {@link ClientKeyMappings}（mod 总线）与
 * {@link ClientDisconnectHandler}（游戏总线）——两类事件不可混挂同一个注册类。
 */
public class ClientModEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ENTITY.get(),
                VideoScreenRenderer::new);
    }
}
