package me.zuogeren.kazumiplayer.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class ClientModEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ENTITY.get(),
                VideoScreenRenderer::new);
    }

    // ---- 快捷键 ----

    /** 模组控制类按键的自定义分类（语言键 key.category.kazumiplayer.main） */
    public static final KeyMapping.Category KAZUMI_CATEGORY =
        new KeyMapping.Category(Identifier.fromNamespaceAndPath("kazumiplayer", "main"));

    /** 静音准心指向的视频屏幕（游戏内默认 M；GUI 打开时不生效） */
    public static final KeyMapping MUTE_SCREEN_MAPPING = new KeyMapping(
        "key.kazumiplayer.mute_screen",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_M,
        KAZUMI_CATEGORY);

    @SubscribeEvent
    public static void registerBindings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KAZUMI_CATEGORY);
        event.register(MUTE_SCREEN_MAPPING);
    }

    /** 游戏内轮询：切换准心指向屏幕的静音状态并聊天栏反馈 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        while (MUTE_SCREEN_MAPPING.consumeClick()) {
            if (mc.player == null || mc.screen != null) continue;
            var be = CrosshairTargetHelper.getTargetScreen();
            if (be == null) continue;
            boolean muted = ScreenPlayerManager.toggleMuted(be.getBlockPos());
            me.zuogeren.kazumiplayer.client.KazumiClientMessages.chatInfo(
                net.minecraft.network.chat.Component.translatable(muted
                        ? "kazumiplayer.msg.mute.on" : "kazumiplayer.msg.mute.off").getString());
        }
    }
}
