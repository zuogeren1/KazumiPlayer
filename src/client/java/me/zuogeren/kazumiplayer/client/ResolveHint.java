package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 解析等待提示的共享装配器：从屏幕 BE 同步的 ResolveStates NBT 统计组内解析进度，
 * 产出单行组件供三处复用——GUI 预览区、全屏 HUD、世界内屏幕面文字。
 * 只有本端是该屏观看者（确实参与该屏的解析/等待）时才产出进度，本端未参与时一律返回 null，
 * 由调用方转显各自的「无信号」语义；本端参与但尚无上报数据时返回 pending 兜底文案
 * （直链起播前的短暂窗口）。
 */
public final class ResolveHint {

    /** 进度快照：total=已上报人数，ready=已出画数，loading=加载中数，failed=失败数 */
    public record Progress(int total, int ready, int loading, int failed) {}

    private ResolveHint() {}

    /** 本端是否为该屏观看者：BE 同步的 WatchingPlayers 含本地玩家 UUID（与客户端起播条件同源） */
    public static boolean isLocalWatching(VideoScreenBlockEntity be) {
        return be != null && isLocalWatchingPlayers(be.getWatchingPlayers());
    }

    /** 同 {@link #isLocalWatching(VideoScreenBlockEntity)}，直接吃 WatchingPlayers 串
     * （世界渲染层只持有 render state 的字段副本，取不到 BE） */
    public static boolean isLocalWatchingPlayers(String watchingPlayers) {
        var player = Minecraft.getInstance().player;
        if (player == null || watchingPlayers == null || watchingPlayers.isEmpty()) return false;
        return java.util.Arrays.asList(watchingPlayers.split(",")).contains(player.getUUID().toString());
    }

    /** 统计当前解析进度；屏幕未在播、本端不是观看者或无人上报时返回 null */
    public static Progress of(VideoScreenBlockEntity be) {
        if (be == null || be.getEpisodeUrl().isEmpty() || !isLocalWatching(be)) return null;
        return ofStates(be.getResolveStates());
    }

    /** 同 {@link #of(VideoScreenBlockEntity)}，直接吃同步过来的状态串（世界渲染层持有 render state 字段）。
     * 调用方自行保证屏幕处于播放会话（episodeUrl 非空） */
    public static Progress ofStates(String resolveStates) {
        Map<UUID, Integer> map = VideoScreenBlockEntity.parseResolveStates(resolveStates);
        if (map.isEmpty()) return null;
        int ready = 0;
        int loading = 0;
        int failed = 0;
        for (int v : map.values()) {
            if (v == ResolveStatusPacket.STATUS_READY) ready++;
            else if (v == ResolveStatusPacket.STATUS_LOADING) loading++;
            else if (v == ResolveStatusPacket.STATUS_FAILED) failed++;
        }
        return new Progress(map.size(), ready, loading, failed);
    }

    /** 组内进度文案：已出画 X/Y（加载中 N 随行显示，失败段标红）；
     *  p 为 null 时返回兜底「正在解析视频源…」（仅出现在本端参与等待但尚无任何上报数据的短暂窗口） */
    public static MutableComponent line(Progress p) {
        if (p == null) {
            return Component.translatable("kazumiplayer.gui.main.resolve_pending");
        }
        MutableComponent text = Component.translatable(
            "kazumiplayer.gui.main.resolve_progress", p.ready(), p.total());
        if (p.loading() > 0) {
            text.append(Component.translatable(
                "kazumiplayer.gui.main.resolve_loading_part", p.loading()));
        }
        if (p.failed() > 0) {
            text.append(Component.translatable(
                "kazumiplayer.gui.main.resolve_failed_part", p.failed())
                .withStyle(ChatFormatting.RED));
        }
        return text;
    }

    /**
     * 本端起播阶段行（加载期提示的第一行）：阶段文案 + 已等秒数 + 量化细节。
     * 只在本端确实在等画面时产出：已出画、或没有该屏的播放状态时返回 null。
     * 阶段来自 {@link ScreenPlayerManager.ScreenPlayer}，细节由播放器状态拼装（都是"在等什么"的硬数据）。
     */
    public static MutableComponent phaseLine(ScreenPlayerManager.ScreenPlayer sp,
            net.minecraft.core.BlockPos pos) {
        // 只在本端确有起播动作（已发起尝试或播放器在册）时产出：未加入该屏/刚清空的屏幕
        // 必须回落调用方的「无信号」语义，不能被默认阶段顶掉
        if (sp == null || sp.firstFrameAt != 0
                || (sp.player == null && sp.startupAttempts == 0)) {
            return null;
        }
        long waitedSec = sp.startupStartedAt > 0
            ? Math.max(0, (System.currentTimeMillis() - sp.startupStartedAt) / 1000)
            : sp.startupElapsedSec();
        MutableComponent phase = Component.translatable(phaseKey(sp.startupPhase));
        // 冷却期：明确告知下一次自动重试的倒计时，否则"起播失败"看起来像终点
        if (sp.startupPhase == ScreenPlayerManager.StartupPhase.FAILED && sp.nextStartupRetryAt > 0) {
            long left = Math.max(0, (sp.nextStartupRetryAt - System.currentTimeMillis() + 999) / 1000);
            if (left > 0) {
                MutableComponent waiting = Component.translatable("kazumiplayer.gui.main.startup_line",
                    Component.translatable("kazumiplayer.gui.main.stage_retry_wait", left),
                    String.valueOf(waitedSec));
                String detail = detailOf(sp, pos);
                if (!detail.isEmpty()) {
                    waiting.append(Component.translatable("kazumiplayer.gui.main.startup_detail", detail));
                }
                return waiting;
            }
        }
        MutableComponent text = Component.translatable("kazumiplayer.gui.main.startup_line",
            phase, String.valueOf(waitedSec));
        String detail = detailOf(sp, pos);
        if (!detail.isEmpty()) {
            text.append(Component.translatable("kazumiplayer.gui.main.startup_detail", detail));
        }
        return text;
    }

    /** 阶段 → 语言键 */
    private static String phaseKey(ScreenPlayerManager.StartupPhase phase) {
        return switch (phase) {
            case RESOLVING -> "kazumiplayer.gui.main.stage_resolving";
            case PREPARING -> "kazumiplayer.gui.main.stage_preparing";
            case OPENING -> "kazumiplayer.gui.main.stage_opening";
            case BUFFERING -> "kazumiplayer.gui.main.stage_buffering";
            case AUDIO_ONLY -> "kazumiplayer.gui.main.stage_audio_only";
            case PAUSED -> "kazumiplayer.gui.main.stage_paused";
            case PLAYING -> "kazumiplayer.gui.main.stage_playing";
            case RETRYING -> "kazumiplayer.gui.main.stage_retrying";
            case FAILED -> "kazumiplayer.gui.main.stage_failed";
        };
    }

    /**
     * 阶段细节：尝试次数 / 档位 / 直播或点播 / 播放位置 / 取址轮询次数 / 失败原因，
     * 逐项按可用性拼接（全部是加载期真实拿到过的数据，不做推测）。
     */
    private static String detailOf(ScreenPlayerManager.ScreenPlayer sp, net.minecraft.core.BlockPos pos) {
        List<String> parts = new java.util.ArrayList<>();
        if (sp.startupAttempts > 1) {
            parts.add(Component.translatable("kazumiplayer.gui.main.detail_attempt",
                String.valueOf(sp.startupAttempts),
                String.valueOf(ClientPlaybackScheduler.startupMaxAttempts())).getString());
        }
        String quality = pos == null ? "" : BilibiliQualityPrefs.currentLabel(pos);
        if (!quality.isEmpty()) {
            parts.add(Component.translatable("kazumiplayer.gui.main.detail_quality", quality).getString());
        }
        var player = sp.player;
        if (player != null) {
            var media = player.getPlayer();
            if (media != null) {
                parts.add(Component.translatable(media.liveSource()
                    ? "kazumiplayer.gui.main.detail_live"
                    : "kazumiplayer.gui.main.detail_vod").getString());
                long timeMs = player.getTimeMs();
                if (timeMs > 0) {
                    parts.add(Component.translatable("kazumiplayer.gui.main.detail_position",
                        String.format(java.util.Locale.ROOT, "%.1f", timeMs / 1000.0)).getString());
                }
            } else if (player.getLoaderPolls() > 1) {
                parts.add(Component.translatable("kazumiplayer.gui.main.detail_mrl_poll",
                    String.valueOf(player.getLoaderPolls())).getString());
            }
        }
        if (!sp.failReasonKey.isEmpty() || !sp.startupExtra.isEmpty()) {
            String reason = sp.failReasonKey.isEmpty()
                ? sp.startupExtra : Component.translatable(sp.failReasonKey).getString();
            if (!sp.failReasonKey.isEmpty() && !sp.startupExtra.isEmpty()) {
                reason = reason + "：" + sp.startupExtra;
            }
            parts.add(Component.translatable("kazumiplayer.gui.main.detail_reason", reason).getString());
        }
        return String.join(" · ", parts);
    }
}
