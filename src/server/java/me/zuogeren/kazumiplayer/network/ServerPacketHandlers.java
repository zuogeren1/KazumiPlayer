package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.network.packet.GuiActionPacket;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.OpenRemoteFullscreenPacket;
import me.zuogeren.kazumiplayer.network.packet.OpenRemoteGuiPacket;
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
            KazumiMessages.sendError(sp, "未找到屏幕方块（可能已被破坏）");
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
            // 通知所有观看者（包括触发者，因为自动切集没有单独提示）
            String name = road.identifier().size() > idx - 1 ? road.identifier().get(idx - 1) : ("第" + idx + "集");
            SyncNotificationUtil.broadcastToGroup(sp, packet.screenPos(), screen.getScreenId(), "自动切换到 " + name);
            KazumiLog.network.info("Auto next episode {}: {}", idx, nextUrl);
        });
    }

    private static void stopPlaybackAndNotify(ServerPlayer triggerPlayer, VideoScreenBlockEntity screen, BlockPos pos) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);

        // 保存实时位置到 NBT（组权威实时值，勿用墙钟）
        if (g != null) {
            screen.updateSyncPosition(g.livePositionMillis());
        }

        // 收集观看者列表（删组前）
        List<UUID> watchers = g != null ? List.copyOf(g.players) : List.of();

        // 停止播放
        screen.clearPlayback();
        SyncGroupManager.get().leaveByScreenId(sid);

        // 停止所有观看者客户端
        var server = ((ServerLevel) triggerPlayer.level()).getServer();
        for (UUID pid : watchers) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) PacketDistributor.sendToPlayer(p, new PlayStopPacket(pos));
        }
        // 通知所有观看者
        SyncNotificationUtil.broadcastToGroup(triggerPlayer, pos, sid, "播放已结束");
        KazumiLog.network.info("Playback ended at {} ({} watchers notified)", pos, watchers.size());
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
                // 建立连接：双向更新
                spk.setLink(packet.screenId(), packet.screenPos());
                screen.addConnectedSpeaker(packet.speakerPos());
                KazumiMessages.sendSuccess(sp, "音响已连接到屏幕 ("
                    + packet.screenPos().getX() + ", " + packet.screenPos().getY() + ", " + packet.screenPos().getZ() + ")");
            } else {
                // 断开连接：双向清空
                spk.clearLink();
                screen.removeConnectedSpeaker(packet.speakerPos());
                KazumiMessages.sendWarn(sp, "音响已断开连接");
            }
        });
    }
}
