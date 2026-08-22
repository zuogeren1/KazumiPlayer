package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.network.packet.GuiActionPacket;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.OpenRemoteGuiPacket;
import me.zuogeren.kazumiplayer.network.packet.RemoteOpenPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.PlaybackControlPacket;
import me.zuogeren.kazumiplayer.network.packet.SpeakerConnectPacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncResponsePacket;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity;
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
            var level = sp.level();
            level.getChunk(packet.screenPos().getX() >> 4, packet.screenPos().getZ() >> 4);
            if (!(level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity)) {
                KazumiMessages.sendError(sp, "未找到屏幕方块（可能已被破坏）");
                return;
            }
            PacketDistributor.sendToPlayer(sp, new OpenRemoteGuiPacket(packet.screenPos()));
            KazumiLog.network.info("Remote open GUI at {} for {}",
                packet.screenPos().toShortString(), sp.getName().getString());
        });
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
            String allData = JsonUtil.GSON.toJson(roads);
            screen.setPlaybackFull(nextUrl, 0, ri, idx, allData);
            UUID sid = screen.getScreenId();
            SyncGroupManager.get().onPlayStart(sp, sid, packet.screenPos(), nextUrl);
            // 同步 WatchingPlayers NBT
            var g = SyncGroupManager.get().getGroup(sid);
            if (g != null) {
                screen.setWatchingPlayers(g.watchingPlayersString());
            }
            // 通知所有观看者（包括触发者，因为自动切集没有单独提示）
            String name = road.identifier().size() > idx - 1 ? road.identifier().get(idx - 1) : ("第" + idx + "集");
            SyncNotificationUtil.broadcastToGroup(sp, packet.screenPos(), sid, "自动切换到 " + name);
            KazumiLog.network.info("Auto next episode {}: {}", idx, nextUrl);
        });
    }

    private static void stopPlaybackAndNotify(ServerPlayer triggerPlayer, VideoScreenBlockEntity screen, BlockPos pos) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);

        // 保存实时位置到 NBT
        if (g != null) {
            long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
            screen.updateSyncPosition(g.positionMs + elapsed);
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

            switch (packet.action()) {
                case "next", "prev" -> handleEpisodeSwitch(sp, screen, packet.action());
                case "pause", "resume" -> togglePause(sp, screen, "pause".equals(packet.action()));
                case "seek_forward" -> {
                    long newPos = Math.max(0, screen.getSyncPositionMs() + packet.value() * 1000);
                    applySeek(sp, screen, newPos);
                }
                case "seek_back" -> {
                    long newPos = Math.max(0, screen.getSyncPositionMs() - packet.value() * 1000);
                    applySeek(sp, screen, newPos);
                }
                case "seek_goto" -> applySeek(sp, screen, packet.value());
            }
        });
    }

    /** seek 后同步权威位置并立即广播，否则周期广播会用旧位置把进度拉回去 */
    private static void applySeek(ServerPlayer sp, VideoScreenBlockEntity screen, long newPos) {
        UUID sid = screen.getScreenId();
        screen.updateSyncPosition(Math.max(0, newPos));
        var g = SyncGroupManager.get().getGroup(sid);
        if (g != null) {
            SyncGroupManager.get().updateState(sid, Math.max(0, newPos), g.paused);
            SyncGroupManager.get().broadcastSyncState(sid, sp.level().getServer());
        }
        SyncNotificationUtil.notifyOtherWatchers(sp, screen.getBlockPos(), sid,
            "跳转到 " + KazumiMessages.formatMs(Math.max(0, newPos)));
    }

    private static void handleEpisodeSwitch(ServerPlayer sp, VideoScreenBlockEntity screen, String action) {
        String data = screen.getEpisodeData();
        if (data.isEmpty()) return;
        // 在当前线路内切换集数（线路下标随播放写入 BE NBT）
        Road road = JsonUtil.parseRoad(data, screen.getRoadIndex());
        if (road == null) return;

        int idx = screen.getEpisodeIndex();
        if ("next".equals(action)) idx++;
        else idx--;
        if (idx < 1 || idx > road.data().size()) return;

        String url = road.data().get(idx - 1);
        screen.setPlaybackFull(url, 0, screen.getRoadIndex(), idx, data);
        String name = road.identifier().size() > idx - 1 ? road.identifier().get(idx - 1) : ("第" + idx + "集");
        SyncNotificationUtil.notifyOtherWatchers(sp, screen.getBlockPos(), screen.getScreenId(),
            "切换到 " + name);
        KazumiLog.network.info("Episode switch to {}: {}", idx, url);
    }

    /** GUI/包触发的暂停/恢复：与 /kazumi pause|resume 同逻辑（更新权威状态并立即广播） */
    private static void togglePause(ServerPlayer sp, VideoScreenBlockEntity screen, boolean pause) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        if (g == null) return;
        long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
        long cur = g.positionMs + elapsed;
        SyncGroupManager.get().updateState(sid, cur, pause);
        screen.updateSyncPosition(cur);
        screen.setPlaybackPaused(pause);
        SyncGroupManager.get().broadcastSyncState(sid, sp.level().getServer());
        SyncNotificationUtil.notifyOtherWatchers(sp, screen.getBlockPos(), sid,
            pause ? "暂停了播放" : "恢复了播放");
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
