package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Map;
import java.util.UUID;

/**
 * 解析等待提示的共享装配器：从屏幕 BE 同步的 ResolveStates NBT 统计组内解析进度，
 * 产出单行组件供三处复用——GUI 预览区、全屏 HUD、世界内屏幕面文字。
 * 无任何上报数据时返回 pending 兜底文案（世界层直链起播前的短暂窗口）。
 */
public final class ResolveHint {

    /** 进度快照：total=已上报人数，ready=完成数，failed=失败数 */
    public record Progress(int total, int ready, int failed) {}

    private ResolveHint() {}

    /** 统计当前解析进度；屏幕未在播或无人上报时返回 null */
    public static Progress of(VideoScreenBlockEntity be) {
        if (be == null || be.getEpisodeUrl().isEmpty()) return null;
        return ofStates(be.getResolveStates());
    }

    /** 同 {@link #of(VideoScreenBlockEntity)}，直接吃同步过来的状态串（世界渲染层持有 render state 字段）。
     * 调用方自行保证屏幕处于播放会话（episodeUrl 非空） */
    public static Progress ofStates(String resolveStates) {
        Map<UUID, Integer> map = VideoScreenBlockEntity.parseResolveStates(resolveStates);
        if (map.isEmpty()) return null;
        int ready = 0;
        int failed = 0;
        for (int v : map.values()) {
            if (v == ResolveStatusPacket.STATUS_READY) ready++;
            else if (v == ResolveStatusPacket.STATUS_FAILED) failed++;
        }
        return new Progress(map.size(), ready, failed);
    }

    /** 单行进度文案（含红色失败段）；p 为 null 时返回兜底「正在解析视频源…」 */
    public static MutableComponent line(Progress p) {
        if (p == null) {
            return Component.translatable("kazumiplayer.gui.main.resolve_pending");
        }
        MutableComponent text = Component.translatable(
            "kazumiplayer.gui.main.resolve_progress", p.ready(), p.total());
        if (p.failed() > 0) {
            text.append(Component.translatable(
                "kazumiplayer.gui.main.resolve_failed_part", p.failed())
                .withStyle(ChatFormatting.RED));
        }
        return text;
    }
}
