package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.network.packet.GuiActionPacket;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.OpenRemoteFullscreenPacket;
import me.zuogeren.kazumiplayer.network.packet.OpenRemoteGuiPacket;
import me.zuogeren.kazumiplayer.network.packet.PositionReportPacket;
import me.zuogeren.kazumiplayer.bilibili.BilibiliApi;
import me.zuogeren.kazumiplayer.network.packet.BilibiliResolveRequestPacket;
import me.zuogeren.kazumiplayer.network.packet.BilibiliResolveResultPacket;
import me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket;
import me.zuogeren.kazumiplayer.network.packet.RemoteFullscreenPacket;
import me.zuogeren.kazumiplayer.network.packet.RemoteOpenPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.PlaybackAction;
import me.zuogeren.kazumiplayer.network.packet.PlaybackControlPacket;
import me.zuogeren.kazumiplayer.network.packet.SpeakerConnectPacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncResponsePacket;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity;
import me.zuogeren.kazumiplayer.sync.PlaybackController;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.util.MonoClock;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端网络包处理器：处理所有 C→S 数据包。
 * 分派采用注册表（包类型 → 处理方法），新增操作只需 static 块注册一行。
 */
public class ServerPacketHandlers implements IServerPacketHandler {

    private static final Map<Class<?>, java.util.function.BiConsumer<CustomPacketPayload, IPayloadContext>> HANDLERS =
        new HashMap<>();

    static {
        register(NextEpisodePacket.class, ServerPacketHandlers::handleNextEpisode);
        register(PlaybackControlPacket.class, ServerPacketHandlers::handlePlaybackControl);
        register(PositionReportPacket.class, ServerPacketHandlers::handlePositionReport);
        register(ResolveStatusPacket.class, ServerPacketHandlers::handleResolveStatus);
        register(BilibiliResolveRequestPacket.class, ServerPacketHandlers::handleBilibiliResolve);
        register(SpeakerConnectPacket.class, ServerPacketHandlers::handleSpeakerConnect);
        register(RemoteOpenPacket.class, ServerPacketHandlers::handleRemoteOpen);
        register(RemoteFullscreenPacket.class, ServerPacketHandlers::handleRemoteFullscreen);
        register(TimeSyncPacket.class, ServerPacketHandlers::handleTimeSync);
        register(GuiActionPacket.class, GuiRequestHandlers::handle);
    }

    private static <T extends CustomPacketPayload> void register(Class<T> cls,
            java.util.function.BiConsumer<T, IPayloadContext> handler) {
        HANDLERS.put(cls, (pkt, ctx) -> handler.accept(cls.cast(pkt), ctx));
    }

    @Override
    public void handle(CustomPacketPayload packet, IPayloadContext context) {
        var h = HANDLERS.get(packet.getClass());
        if (h != null) h.accept(packet, context);
    }

    /** 时钟同步探测：回显客户端发送时刻并附上服务端收/发两端单调毫秒（须在主线程外尽快响应） */
    private static void handleTimeSync(TimeSyncPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        long recv = MonoClock.millis();
        long send = MonoClock.millis();
        PacketDistributor.sendToPlayer(sp,
                new TimeSyncResponsePacket(packet.clientSendMonotonicMs(), recv, send));
    }

    /** 屏幕遥控器：校验绑定屏幕存在（远程屏幕通常不在视距内，强制加载区块）后让客户端打开 GUI */
    private static void handleRemoteOpen(RemoteOpenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (validateRemoteTarget(sp, packet.screenPos())) {
                PacketDistributor.sendToPlayer(sp, new OpenRemoteGuiPacket(packet.screenPos()));
            }
        });
    }

    /** 全屏遥控器：同上校验，通过后让客户端直接进入全屏观影 */
    private static void handleRemoteFullscreen(RemoteFullscreenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (validateRemoteTarget(sp, packet.screenPos())) {
                PacketDistributor.sendToPlayer(sp, new OpenRemoteFullscreenPacket(packet.screenPos()));
            }
        });
    }

    /** 远程目标校验：强制加载所在区块并确认是屏幕方块，失败时提示。返回 true 表示有效 */
    private static boolean validateRemoteTarget(ServerPlayer sp, BlockPos pos) {
        var level = sp.level();
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (!(level.getBlockEntity(pos) instanceof VideoScreenBlockEntity)) {
            KazumiMessages.sendErrorKey(sp, "kazumiplayer.err.screen_missing");
            return false;
        }
        KazumiLog.network.info("Remote open at {} for {}", pos.toShortString(), sp.getName().getString());
        return true;
    }

    private static void handleNextEpisode(NextEpisodePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return;

            String data = screen.getEpisodeData();
            // 无 EpisodeData（play-url 等）：播放完毕直接停止
            if (data.isEmpty()) {
                stopPlaybackAndNotify(sp, screen, packet.screenPos());
                return;
            }
            int idx = screen.getEpisodeIndex() + 1; // next episode
            List<Road> roads = JsonUtil.GSON.fromJson(data,
                new com.google.gson.reflect.TypeToken<List<Road>>() {}.getType());
            if (roads == null || roads.isEmpty()) {
                stopPlaybackAndNotify(sp, screen, packet.screenPos());
                return;
            }
            // 自动下一集保持在当前线路内（线路下标随播放写入 BE NBT）
            int ri = Math.max(0, Math.min(screen.getRoadIndex(), roads.size() - 1));
            Road road = roads.get(ri);
            if (idx < 1 || idx > road.data().size()) {
                // 没有下一集：停止播放并通知
                stopPlaybackAndNotify(sp, screen, packet.screenPos());
                return;
            }

            String nextUrl = road.data().get(idx - 1);
            PlaybackController.applyEpisodeSwitch(sp, packet.screenPos(), screen, nextUrl, ri, idx,
                JsonUtil.GSON.toJson(roads));
            // 通知所有观看者（包括触发者，因为自动切集没有单独提示）；集名回退为嵌套 translatable
            Component name = road.identifier().size() > idx - 1 ? Component.literal(road.identifier().get(idx - 1))
                    : Component.translatable("kazumiplayer.gui.main.episode_n", idx);
            SyncNotificationUtil.broadcastToGroup(sp, packet.screenPos(), screen.getScreenId(),
                Component.translatable("kazumiplayer.msg.notify.auto_switched", name));
            KazumiLog.network.info("Auto next episode {}: {}", idx, nextUrl);
        });
    }

    private static void stopPlaybackAndNotify(ServerPlayer triggerPlayer, VideoScreenBlockEntity screen, BlockPos pos) {
        // 唯一权威实现；actor=null 表示自动事件：PlayStopPacket 与通知均发给全组（含触发者）
        PlaybackController.stopScreen(null, pos, screen,
                Component.translatable("kazumiplayer.msg.notify.playback_ended"));
        KazumiLog.network.info("Playback ended at {}", pos);
    }

    private static void handlePlaybackControl(PlaybackControlPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return;

            // 全部委托 PlaybackController（与命令层同一权威实现），结果静默（GUI 场景无聊天反馈惯例）
            switch (packet.action()) {
                case NEXT -> PlaybackController.switchEpisode(sp, packet.screenPos(), screen, true);
                case PREV -> PlaybackController.switchEpisode(sp, packet.screenPos(), screen, false);
                case PAUSE -> PlaybackController.setPaused(sp, packet.screenPos(), screen, true);
                case RESUME -> PlaybackController.setPaused(sp, packet.screenPos(), screen, false);
                case SEEK_FORWARD -> PlaybackController.seekTo(sp, packet.screenPos(), screen,
                    PlaybackController.livePosition(screen) + packet.value() * 1000);
                case SEEK_BACK -> PlaybackController.seekTo(sp, packet.screenPos(), screen,
                    PlaybackController.livePosition(screen) - packet.value() * 1000);
                case SEEK_GOTO -> PlaybackController.seekTo(sp, packet.screenPos(), screen, packet.value());
            }
        });
    }

    /** 首帧锚定上报：新集出画后以真实播放位置校准组时钟（仅每次切集后的第一次生效），并立即广播对齐全组 */
    private static void handlePositionReport(PositionReportPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return;
            if (!packet.screenId().equals(screen.getScreenId())) return;
            var g = SyncGroupManager.get().getGroup(packet.screenId());
            if (g == null || !g.players.contains(sp.getUUID())) return;
            if (!SyncGroupManager.get().acceptAnchor(packet.screenId(), packet.positionMs())) return;
            SyncGroupManager.get().broadcastSyncState(packet.screenId(), sp.level().getServer());
            KazumiLog.sync.debug("Anchor established at {} by {} ({}ms)",
                packet.screenPos(), sp.getName().getString(), packet.positionMs());
        });
    }

    /**
     * 视频源解析状态上报：校验屏幕/组成员后聚合写入 BE 的 ResolveStates NBT，
     * 值变化时经 markDirty 自动同步全组客户端（GUI 观看者列表据此展示谁未就绪）。
     */
    private static void handleResolveStatus(ResolveStatusPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return;
            if (!packet.screenId().equals(screen.getScreenId())) return;
            var g = SyncGroupManager.get().getGroup(packet.screenId());
            if (g == null || !g.players.contains(sp.getUUID())) return;
            if (screen.updateResolveState(sp.getUUID(), packet.status())) {
                KazumiLog.sync.debug("Resolve status {} at {} by {}",
                    packet.status(), packet.screenPos(), sp.getName().getString());
            }
        });
    }

    /**
     * B 站代理解析：凭据只留在服务端（不再下发到客户端），解析产物（有时效的 mp4 直链 / 直播 m3u8）
     * 连同档位表下发给请求者；失败回传原因，客户端据此用本地凭据回落自解析。
     * 校验与解析状态上报同款：屏幕存在 → screenId 一致 → 请求者是该屏观看组成员。
     */
    private static void handleBilibiliResolve(BilibiliResolveRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return;
            if (!packet.screenId().equals(screen.getScreenId())) return;
            var group = SyncGroupManager.get().getGroup(packet.screenId());
            if (group == null || !group.players.contains(sp.getUUID())) return;

            String cookie = me.zuogeren.kazumiplayer.ServerConfig.CONFIG.bilibiliCookie.get();
            String pageUrl = packet.pageUrl();
            boolean live = BilibiliApi.liveRoomId(pageUrl) > 0;
            KazumiLog.sniff.info("[bilibili] server-side resolve by {} for {} (qn={}, live={}, credentials={})",
                sp.getName().getString(), pageUrl, packet.preferredQn(), live,
                cookie == null || cookie.isBlank() ? "anonymous" : "configured");
            var future = live
                ? BilibiliApi.resolveLive(pageUrl, packet.preferredQn(), cookie)
                : BilibiliApi.resolveVideo(pageUrl, packet.preferredQn(), cookie);
            future.whenComplete((stream, t) -> {
                BilibiliResolveResultPacket result = t != null
                    ? new BilibiliResolveResultPacket(packet.screenPos(), packet.requestId(), pageUrl,
                        false, "", "", "", 0, String.valueOf(unwrapFuture(t).getMessage()), 0L, 0L)
                    : new BilibiliResolveResultPacket(packet.screenPos(), packet.requestId(), pageUrl,
                        true, stream.url(), stream.audioUrl(), BilibiliApi.encodeQualities(stream.qualities()),
                        stream.currentQn(), "", stream.cid(), stream.liveRoomId());
                var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                // 解析在异步线程完成：回到服务端线程再发包
                server.execute(() -> {
                    ServerPlayer target = server.getPlayerList().getPlayer(sp.getUUID());
                    if (target != null) PacketDistributor.sendToPlayer(target, result);
                });
            });
        });
    }

    /** 剥掉 CompletableFuture 的包装异常，取真实原因 */
    private static Throwable unwrapFuture(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException
                || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static void handleSpeakerConnect(SpeakerConnectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;

            // 校验两个方块都存在
            var spkBe = sp.level().getBlockEntity(packet.speakerPos());
            if (!(spkBe instanceof SpeakerBlockEntity spk)) return;
            var scrBe = sp.level().getBlockEntity(packet.screenPos());
            if (!(scrBe instanceof VideoScreenBlockEntity screen)) return;

            // 校验 screenId 匹配
            if (!packet.screenId().equals(screen.getScreenId())) return;

            if (packet.connect()) {
                // 建立连接：双向更新（坐标为纯字面量参数）
                spk.setLink(packet.screenId(), packet.screenPos());
                screen.addConnectedSpeaker(packet.speakerPos());
                KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.notify.speaker_connected",
                    String.valueOf(packet.screenPos().getX()), String.valueOf(packet.screenPos().getY()),
                    String.valueOf(packet.screenPos().getZ()));
            } else {
                // 断开连接：双向清空
                spk.clearLink();
                screen.removeConnectedSpeaker(packet.speakerPos());
                KazumiMessages.sendWarnKey(sp, "kazumiplayer.msg.notify.speaker_disconnected");
            }
        });
    }
}
