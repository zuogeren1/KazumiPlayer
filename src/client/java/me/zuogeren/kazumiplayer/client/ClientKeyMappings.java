package me.zuogeren.kazumiplayer.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 模组快捷键注册（仅挂 mod 事件总线）。
 * RegisterKeyMappingsEvent 属 mod 总线事件——与游戏总线事件混在同一类里会被
 * 双侧扫描拒绝加载（实例崩溃：@SubscribeEvent 方法参数对本总线无效），故独立成类。
 * 按键消费轮询在游戏总线侧（ClientDisconnectHandler 的每 tick 处理）。
 */
public class ClientKeyMappings {

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
}
