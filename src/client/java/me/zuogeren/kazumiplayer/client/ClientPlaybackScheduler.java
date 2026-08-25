package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.network.packet.PositionReportPacket;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * 客户端播放调度核心（每秒一轮，从 ClientDisconnectHandler.onClientTick 拆出）：
 * <ol>
 *   <li>对账 ScreenPlayerManager 与世界状态（回收孤儿/过期条目）</li>
 *   <li>单次遍历渲染范围方块实体，分派视频屏幕六段调度与音响 tick</li>
 * </ol>
 * 段间存在刻意的顺序约定（详见 tickVideoScreen 内各段注释），修改前先推演时序。
 */
public class ClientPlaybackScheduler {
    // ---- 调度节奏常量 ----
    private static final int TICKS_PER_SECOND = 20;                 // 播放调度每秒一轮
    private static final long PLAYBACK_START_COOLDOWN_MS = 3000;    // 开播冷却，防同步风暴期反复起播
    private static final long END_DETECT_MARGIN_MS = 300;           // 播完判定提前量（live 型 HLS 无 EOF 兜底）

    private static int tickCounter;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++tickCounter % TICKS_PER_SECOND != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // 主菜单空转守卫：仅在有残留状态时清理一次，避免每秒三连 stop
            if (!ScreenPlayerManager.getAll().isEmpty() || !SpeakerClientAudio.getActivePositions().isEmpty()) {
                stopAllActive();
            }
            return;
        }
        // 先对账：Manager 中存在但世界已对不上的条目（屏幕移除/停止播放/空闲残留）
        reconcileStaleEntries(mc);
        // 单次遍历渲染范围 BE：视频屏幕调度 + 音响 tick
        long zombieTimeoutMs = Math.max(75_000,
            me.zuogeren.kazumiplayer.ClientConfig.CONFIG.sniffTimeoutSeconds.get() * 2000L + 15_000);
        for (var be : mc.level.getGloballyRenderedBlockEntities()) {
            if (be instanceof VideoScreenBlockEntity screen) {
                tickVideoScreen(screen, mc, zombieTimeoutMs);
            } else if (be instanceof me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity spk) {
                SpeakerClientAudio.tick(spk);
            }
        }
        // 音响方块被移除兜底：BE 不再渲染，上面的遍历不会触发 → 按位置检查并清理音频
        for (var pos : SpeakerClientAudio.getActivePositions()) {
            var be = mc.level.getBlockEntity(pos);
            if (!(be instanceof me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity)
                    || be.isRemoved()) {
                SpeakerClientAudio.remove(pos);
            }
        }
    }

    /**
     * 对账：ScreenPlayerManager 条目反查世界状态。
     * - 有播放器但屏幕 BE 已消失/被移除/已停止播放（url 空）→ 停止并清除；
     *   注意 chunk 卸载时 getBlockEntity 返回 null 同样判为消失——远离渲染距离即断播是已知约束
     * - 从未播放且无上次 URL 的空闲占位条目 → 移除，防止注册表无限增长
     */
    private static void reconcileStaleEntries(Minecraft mc) {
        for (var sp : ScreenPlayerManager.getAll().entrySet()) {
            var be = mc.level.getBlockEntity(sp.getKey());
            boolean screenGone = !(be instanceof VideoScreenBlockEntity screen)
                    || screen.isRemoved();
            boolean noLongerWatching = be instanceof VideoScreenBlockEntity screen
                    && screen.getEpisodeUrl().isEmpty();
            if (sp.getValue().player != null && (screenGone || noLongerWatching)) {
                sp.getValue().player.stop();
                sp.getValue().player = null;
                ScreenPlayerManager.remove(sp.getKey());
                KazumiLog.playback.info("Cleaned up stale playback at {}", sp.getKey());
            } else if (sp.getValue().player == null && sp.getValue().lastEpisodeUrl.isEmpty()) {
                ScreenPlayerManager.remove(sp.getKey());
            }
        }
    }

    /** 每屏限流提示时间戳：达播放上限时 15s 一次，避免每秒刷屏 */
    private static final java.util.Map<net.minecraft.core.BlockPos, Long> PLAY_LIMIT_NOTICE_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 客户端最大同时播放屏数（maxConcurrentPlays 配置）：活跃播放器计数是否仍在预算内 */
    private static boolean withinConcurrentPlayBudget() {
        long active = ScreenPlayerManager.getAll().values().stream()
                .filter(s -> s.player != null).count();
        return active < me.zuogeren.kazumiplayer.ClientConfig.CONFIG.maxConcurrentPlays.get();
    }

    private static void notifyPlayLimitThrottled(net.minecraft.core.BlockPos pos) {
        long now = System.currentTimeMillis();
        if (now - PLAY_LIMIT_NOTICE_AT.getOrDefault(pos, 0L) > 15000) {
            PLAY_LIMIT_NOTICE_AT.put(pos, now);
            KazumiClientMessages.chatWarn(me.zuogeren.kazumiplayer.util.KazumiMessages
                .warnKey("kazumiplayer.msg.warn.play_limit",
                    String.valueOf(me.zuogeren.kazumiplayer.ClientConfig.CONFIG.maxConcurrentPlays.get()),
                    pos.toShortString()).getString());
        }
    }

    /**
     * 单个视频屏幕的六段调度。段落间有顺序约定，勿随意调换：
     * 僵尸自愈 → 新播放 → pendingSeek 重试 → 音量 → ended 检测 → URL 变更停旧。
     * （例：URL 变更段依赖 start 段写入 lastEpisodeUrl 来吃掉"player==null 时的新 URL"，
     * 使变更段只在 player!=null 时触发杀旧逻辑。）
     */
    private static void tickVideoScreen(VideoScreenBlockEntity screen, Minecraft mc, long zombieTimeoutMs) {
        String url = screen.getEpisodeUrl();
        var sp = ScreenPlayerManager.get(screen.getBlockPos());
        // 僵尸播放器自愈：解析失败/租约放弃/嗅探超时耗尽后，播放器已登记但永不进入播放态，
        // 而重试条件是 player == null——不回收就永远卡死（只能离开重新加入才能恢复）。
        // 阈值覆盖两次嗅探超时重试（默认 30s×2）+ 起播缓冲；暂停中的屏幕不算僵尸
        if (sp.player != null && !sp.everPlayed && !screen.isPlaybackPaused()
                && System.currentTimeMillis() - sp.playbackStartedAt > zombieTimeoutMs) {
            KazumiLog.playback.warn("Zombie playback (never started) at {}, recycling",
                    screen.getBlockPos());
            sp.player.stop();
            sp.player = null;
            sp.everPlayed = false;
        }
        // 新播放：启动播放器并预置 seek
        // 只有 WatchingPlayers 中的玩家才自动播放（手动 join 后才能播）
        if (sp.player == null && !url.isEmpty() && isWatching(screen, mc)
                && System.currentTimeMillis() - sp.playbackStartedAt > PLAYBACK_START_COOLDOWN_MS) {
            // 客户端最大同时播放屏数：达上限不再为新屏起播（N 屏 = N 路 FFmpeg 全速运行，必须有资源闸）
            if (!withinConcurrentPlayBudget()) {
                notifyPlayLimitThrottled(screen.getBlockPos());
                return;
            }
            sp.playbackStartedAt = System.currentTimeMillis();
            sp.bypassSync = false; // 先复位上一集的直播直连标记（beginPlayback 检测到直播直链会重新置位）
            sp.player = me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver
                    .getInstance().beginPlayback(screen, url);
            sp.lastEpisodeUrl = url;
            sp.endedNotified = true; // 防止新播放器初始化期间误触发 isEnded()
            sp.everPlayed = false;
            sp.anchorReported = false; // 新集重新做首帧锚定上报
            sp.nextPrefetched = false; // 新集重新评估下一集预解析
            sp.hasPausedState = false; // 新播放器重新建立暂停态基线（首广播必应用）
            long seekMs = screen.getSyncPositionMs();
            if (sp.player != null && seekMs > 0) sp.player.seek(seekMs);
            // 启动快照：屏幕处于暂停时立即暂停（不再依赖每秒轮询）
            if (screen.isPlaybackPaused()) {
                sp.player.pause();
            }
            if (sp.player != null) {
                attachFailureSkip(screen, sp, mc);
            }
        }
        // 已启动但 seek 未生效：等播放器就绪后重试
        if (sp.player != null && sp.player.hasPendingSeek() && sp.player.isPlaying()) {
            sp.player.applyPendingSeek();
        }
        // 音量：RECORDS×videoVolume 总量之上叠加每屏独立系数（静音为 0）
        //（暂停/恢复状态由 SyncStatePacket 推送，不再每秒轮询 NBT）
        if (sp.player != null) {
            sp.player.applyVolumeFromOptions(sp.muted ? 0f : sp.volumeScale);
            // 缓冲冻结跟踪：位置推进即刷新时刻，供渲染层停滞判定（重缓冲指示）
            long time = sp.player.getTimeMs();
            if (time != sp.lastTimeMs) {
                sp.lastTimeMs = time;
                sp.lastTimeAdvancedAt = System.currentTimeMillis();
            }
        }
        // 检测播放完毕 → 自动下一集
        if (sp.player != null) {
            // 开播即解除上面的初始化保护——否则 endedNotified 恒为 true，
            // 播放自然结束后永远无法触发自动下一集（也顺带覆盖了"播完手动 seek 回去再看"的场景）
            if (sp.player.isPlaying()) {
                sp.endedNotified = false;
                if (!sp.everPlayed) {
                    sp.everPlayed = true; // 已实际出画面，不参与僵尸自愈判定
                    // 首帧锚定上报（每集一次）：服务端以真实播放位置校准权威时钟，
                    // 消除"组时钟从切换瞬间流逝 vs 播放器经解析/缓冲晚 N 秒出声"的假漂移硬 seek；
                    // 直播直连模式无权威时间轴，上报无意义
                    if (!sp.bypassSync && !sp.anchorReported && mc.getConnection() != null) {
                        sp.anchorReported = true;
                        mc.getConnection().send(new ServerboundCustomPayloadPacket(
                            new PositionReportPacket(screen.getBlockPos(), screen.getScreenId(),
                                sp.player.getTimeMs())));
                    }
                }
                // 稳定播放中：借用空闲租约预解析下一集（每集一次），消除集间嗅探黑窗
                if (!sp.bypassSync && !sp.nextPrefetched) {
                    sp.nextPrefetched = true;
                    String nextUrl = findNextEpisodeUrl(screen);
                    if (nextUrl != null) {
                        me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver.getInstance()
                            .prefetchNext(screen.getBlockPos(), nextUrl);
                    }
                }
            }
            // 播完判定：直播直连模式只认真实 EOF——滑动窗口 duration 是固定窗长
            // （实测 live 探针 totalDuration=30s），"时长逼近视为播完"兜底对 live 必然误触发；
            // VOD 型 HLS（m3u8 点播）没有 EOF 缺失问题，仍靠 isEnded() 正常驱动队列自动连播
            boolean ended;
            if (sp.bypassSync) {
                ended = sp.player.isEnded();
            } else {
                ended = sp.player.isEnded()
                        || (sp.player.getDurationMs() > 0
                        && sp.player.getTimeMs() >= sp.player.getDurationMs() - END_DETECT_MARGIN_MS);
            }
            if (ended && !sp.endedNotified && url.equals(sp.lastEpisodeUrl)) {
                // url 守卫：服务端已切到下一集而旧播放器尚存活的 1~2 个 tick 里，
                // EOF 处 isPlaying 仍为 true 会把 endedNotified 重新解除并再次上报——连续切两集跳集。
                // 仅对本端自己起播的那一集上报播完；换集后的旧播放器随 URL 变更段停止，不参与判定。
                sp.endedNotified = true;
                KazumiLog.playback.info("Auto-next: ended detected at screen {}", screen.getBlockPos());
                var pkt = new NextEpisodePacket(screen.getBlockPos());
                mc.getConnection().send(new ServerboundCustomPayloadPacket(pkt));
            }
        }
        // URL 变了 → 取消该屏在途解析（防止旧解析完成后复活已停止的旧播放器）、停旧播放器，下次 tick 自动启动新的
        if (!url.isEmpty() && !url.equals(sp.lastEpisodeUrl)) {
            me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver.getInstance()
                    .cancelResolve(screen.getBlockPos());
            if (sp.player != null) {
                sp.player.stop();
                sp.player = null;
            }
            sp.lastEpisodeUrl = url;
            sp.endedNotified = false;
            // 换集清屏：上一集的在途/驻留弹幕作废（对即时项幂等双保险）
            me.zuogeren.kazumiplayer.client.danmaku.ClientDanmakuStore.clear(screen.getBlockPos());
        }
    }

    /** 离开世界/断线：停止所有本地播放 */
    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        stopAllActive();
        // 弹幕状态全量回收：pending 队列与两层活动列表（BE 移除后 draw 停调，滞留条目靠此兜底）
        me.zuogeren.kazumiplayer.client.danmaku.ClientDanmakuStore.clearAll();
        me.zuogeren.kazumiplayer.client.danmaku.DanmakuWorldLayer.reset();
        me.zuogeren.kazumiplayer.client.danmaku.DanmakuHudLayer.reset();
    }

    // ---- 队列容错：播放失败自动跳过 ----

    /**
     * 从 BE 剧集数据解析当前线路的下一集 URL（无数据/末集返回 null）。
     * 供预解析触发：仅读 NBT 快照，不做任何服务端交互。
     */
    private static String findNextEpisodeUrl(VideoScreenBlockEntity screen) {
        String data = screen.getEpisodeData();
        if (data.isEmpty()) return null;
        List<Road> roads = me.zuogeren.kazumiplayer.util.JsonUtil.GSON.fromJson(data,
            new com.google.gson.reflect.TypeToken<List<Road>>() {}.getType());
        if (roads == null || roads.isEmpty()) return null;
        int ri = Math.max(0, Math.min(screen.getRoadIndex(), roads.size() - 1));
        Road road = roads.get(ri);
        int next = screen.getEpisodeIndex() + 1; // 1-based 当前项的下一项
        if (next < 1 || next > road.data().size()) return null;
        return road.data().get(next - 1);
    }

    /** 同一屏幕跳过请求的最小重发间隔（服务端处理与 URL 变更同步均有延迟，防抖动重复发送） */
    private static final long SKIP_RESEND_COOLDOWN_MS = 3000;
    private static long lastSkipSentAt;

    /**
     * 给本次起播的播放器挂失败回调：MRL 拒绝/加载超时/播放器创建失败时
     * 请求服务端移除正在播放的队列项并切播下一个（queue_skip_current）。
     * 回调可能来自 MRL-Loader 线程，统一投递主线程；会话不符（URL 已变更/已停止）则忽略。
     */
    private static void attachFailureSkip(VideoScreenBlockEntity screen,
            ScreenPlayerManager.ScreenPlayer sp, Minecraft mc) {
        final var player = sp.player;
        player.setPlayFailureListener(() -> mc.execute(() -> {
            var current = ScreenPlayerManager.get(screen.getBlockPos());
            if (current.player != player) return; // 该播放器已被替换/停止，失败属于旧集
            if (mc.getConnection() == null) return;
            // 记录失败快照：GUI/全屏据此在窗口期内展示失败横幅与指引
            current.lastFailedUrl = screen.getEpisodeUrl();
            current.lastFailedAt = System.currentTimeMillis();
            long now = System.currentTimeMillis();
            if (now - lastSkipSentAt < SKIP_RESEND_COOLDOWN_MS) return;
            lastSkipSentAt = now;
            KazumiLog.playback.info("Playback failed at {}, requesting queue skip",
                screen.getBlockPos());
            mc.getConnection().send(new ServerboundCustomPayloadPacket(
                new me.zuogeren.kazumiplayer.network.packet.GuiActionPacket(
                    screen.getBlockPos(),
                    me.zuogeren.kazumiplayer.network.gui.GuiProtocol.ACTION_QUEUE_SKIP_CURRENT,
                    "{}")));
        }));
    }

    /** 单机世界退出：集成服务端停止时兜底停止所有播放（客户端连远程服时由 LoggingOut 兜底） */
    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        // 事件在服务端线程触发，调度到渲染线程执行（WaterMedia 播放器需主线程操作）
        Minecraft.getInstance().execute(ClientPlaybackScheduler::stopAllActive);
    }

    private static void stopAllActive() {
        // 取消全部在途解析（否则解析完成后起播——虽有会话守卫弃播，但让浏览器白跑一趟）
        me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver.getInstance().cancelAllResolves();
        // 先统一 stop 所有播放器，再清空跟踪集合（remove 也会 stop，但显式 stopAll 保证顺序）
        ScreenPlayerManager.stopAll();
        SpeakerClientAudio.stopAll();
    }

    private static boolean isWatching(VideoScreenBlockEntity screen, Minecraft mc) {
        if (mc.player == null) return false;
        String watchers = screen.getWatchingPlayers();
        if (watchers.isEmpty()) return false;
        return java.util.Arrays.asList(watchers.split(","))
                .contains(mc.player.getUUID().toString());
    }
}
