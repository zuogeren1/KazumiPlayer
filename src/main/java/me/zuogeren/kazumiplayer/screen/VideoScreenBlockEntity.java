package me.zuogeren.kazumiplayer.screen;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import net.minecraft.core.BlockPos;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

public class VideoScreenBlockEntity extends BlockEntity {

    // ---- NBT 字段名（loadAdditional / saveAdditional / getUpdateTag 三处共用，勿散落字符串） ----
    private static final String KEY_SCREEN_WIDTH = "ScreenWidth";
    private static final String KEY_SCREEN_HEIGHT = "ScreenHeight";
    private static final String KEY_FACING = "Facing";
    private static final String KEY_VIDEO_STATE = "VideoState";
    private static final String KEY_EPISODE_URL = "EpisodeUrl";
    private static final String KEY_SYNC_POSITION_MS = "SyncPositionMs";
    private static final String KEY_EPISODE_INDEX = "EpisodeIndex";
    private static final String KEY_ROAD_INDEX = "RoadIndex";
    private static final String KEY_EPISODE_DATA = "EpisodeData";
    private static final String KEY_PLAYING_TITLE = "PlayingTitle";
    private static final String KEY_WATCHING_PLAYERS = "WatchingPlayers";
    private static final String KEY_PLAYBACK_PAUSED = "PlaybackPaused";
    private static final String KEY_SKIN_BLOCK = "SkinBlock";
    private static final String KEY_SCREEN_ID = "ScreenId";
    private static final String KEY_OFFSET_X = "OffsetX";
    private static final String KEY_OFFSET_Y = "OffsetY";
    private static final String KEY_OFFSET_Z = "OffsetZ";
    private static final String KEY_CONNECTED_SPEAKERS = "ConnectedSpeakers";

    private float screenWidth = 3.0f;
    private float screenHeight = 2.0f;
    private Direction facing = Direction.NORTH;
    private VideoState videoState = VideoState.IDLE;

    // 播放状态
    private String episodeUrl = "";
    private long syncPositionMs = -1;

    // 集数管理
    private int episodeIndex = 1;          // 当前集数 (1-based)
    private int roadIndex = 0;             // 当前线路 (0-based，指向 episodeData 中的 Road)
    private String episodeData = "";       // Road JSON（所有集的名称+URL）
    private String playingTitle = "";      // 番剧名称（规则播放时写入；直链播放保持空，GUI 不显示"正在播放"行）
    private String watchingPlayers = "";   // 观看者 UUID 列表，逗号分隔
    private boolean playbackPaused;
    private String skinBlock = "";         // 方块皮肤 ID，空=默认
    private UUID screenId;                 // 屏幕唯一标识，lazy 生成
    private float offsetX;                 // 屏幕面渲染偏移（格），XYZ 自由叠加在默认位置上
    private float offsetY;
    private float offsetZ;
    private final List<BlockPos> connectedSpeakers = new ArrayList<>(); // 已连接音响列表

    public VideoScreenBlockEntity(BlockPos pos, BlockState blockState) {
        super(VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public float getScreenWidth() { return screenWidth; }
    public float getScreenHeight() { return screenHeight; }
    public Direction getFacing() { return facing; }
    public VideoState getVideoState() { return videoState; }
    public String getEpisodeUrl() { return episodeUrl; }
    public long getSyncPositionMs() { return syncPositionMs; }
    public int getEpisodeIndex() { return episodeIndex; }
    public int getRoadIndex() { return roadIndex; }
    public String getEpisodeData() { return episodeData; }
    public String getPlayingTitle() { return playingTitle; }
    public void setPlayingTitle(String title) {
        this.playingTitle = title == null ? "" : title;
        markDirty();
    }
    public String getWatchingPlayers() { return watchingPlayers; }
    public boolean isPlaybackPaused() { return playbackPaused; }
    public String getSkinBlock() { return skinBlock; }
    public List<BlockPos> getConnectedSpeakers() { return connectedSpeakers; }
    public void addConnectedSpeaker(BlockPos pos) {
        if (!connectedSpeakers.contains(pos)) { connectedSpeakers.add(pos); markDirty(); }
    }
    public void removeConnectedSpeaker(BlockPos pos) {
        connectedSpeakers.remove(pos); markDirty();
    }
    /**
     * 屏幕唯一标识。首次访问时 lazy 生成并 markDirty 持久化（历史行为，全部服务端操作路径
     * 依赖此语义，勿改为纯 getter）。新代码建议在创建屏幕后显式调用 {@link #ensureScreenId()}。
     */
    public UUID getScreenId() {
        return ensureScreenId();
    }

    /** 显式获取或初始化 ScreenId（生成即持久化）——把副作用从"任意读取路径"收敛到明确调用点 */
    public UUID ensureScreenId() {
        if (screenId == null) {
            screenId = UUID.randomUUID();
            markDirty();
        }
        return screenId;
    }

    public void setScreenSize(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
        markDirty();
    }

    public void setFacing(Direction facing) {
        this.facing = facing;
        markDirty();
    }

    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
    public float getOffsetZ() { return offsetZ; }

    /** 设置屏幕面渲染偏移（格），叠加在默认位置上 */
    public void setScreenOffset(float x, float y, float z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
        markDirty();
    }

    public void setVideoState(VideoState state) {
        this.videoState = state;
        markDirty();
    }

    /**
     * 简单播放：仅设置 URL/位置/状态，不写线路/集数/Road 数据（保留原值）。
     * 适用：无剧集上下文的场景（如暂停恢复后的重定位）。规则剧集与直链队列
     * 必须用 {@link #setPlaybackFull}，否则自动连播会读到过期 Road 数据。
     */
    public void setPlayback(String url, long positionMs) {
        this.episodeUrl = url;
        this.syncPositionMs = positionMs;
        // 空 URL 视为未播放：状态保持 IDLE，不残留 SyncPositionMs
        this.videoState = url.isEmpty() ? VideoState.IDLE : VideoState.PLAYING;
        if (url.isEmpty()) this.syncPositionMs = -1;
        markDirty();
        KazumiLog.screen.info("setPlayback url={} posMs={} side={}", url, positionMs,
            level != null && level.isClientSide() ? "client" : "server");
    }

    /**
     * 完整播放：写入 URL/位置/状态 + 线路/集数/Road 数据。
     * 适用：规则剧集播放与直链队列（合成 Road，见 util/DirectLinkQueue）——
     * 自动下一集/上一集/切线均在 EpisodeData 内推进，必须经此方法写入。
     */
    public void setPlaybackFull(String url, long positionMs, int roadIdx, int episodeIdx, String episodeDataJson) {
        this.episodeUrl = url;
        this.syncPositionMs = positionMs;
        this.roadIndex = roadIdx;
        this.episodeIndex = episodeIdx;
        this.episodeData = episodeDataJson;
        this.videoState = VideoState.PLAYING;
        markDirty();
    }

    public void clearPlayback() {
        this.episodeUrl = "";
        this.syncPositionMs = -1;
        this.episodeIndex = 1;
        this.roadIndex = 0;
        this.episodeData = "";
        this.playingTitle = "";
        this.videoState = VideoState.IDLE;
        markDirty();
        KazumiLog.screen.info("clearPlayback side={}",
            level != null && level.isClientSide() ? "client" : "server");
    }

    public void updateSyncPosition(long positionMs) {
        this.syncPositionMs = positionMs;
        markDirty();
    }

    /** 只更新剧集/队列数据（如队列插队、移除项），不动当前播放状态 */
    public void setEpisodeData(String episodeDataJson) {
        this.episodeData = episodeDataJson == null ? "" : episodeDataJson;
        markDirty();
    }

    /** 只更新当前项序号（1-based），如队列移除待播区之前项后的序号修正 */
    public void setEpisodeIndex(int index) {
        this.episodeIndex = index;
        markDirty();
    }

    public void setWatchingPlayers(String players) {
        this.watchingPlayers = players;
        markDirty();
    }

    public void setPlaybackPaused(boolean paused) {
        this.playbackPaused = paused;
        markDirty();
    }

    public void setSkinBlock(String id) {
        this.skinBlock = id;
        markDirty();
    }

    private void markDirty() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        this.screenWidth = input.getFloatOr(KEY_SCREEN_WIDTH, 3.0f);
        this.screenHeight = input.getFloatOr(KEY_SCREEN_HEIGHT, 2.0f);
        this.facing = input.getString(KEY_FACING).map(Direction::byName).orElse(Direction.NORTH);
        this.videoState = input.getString(KEY_VIDEO_STATE).map(VideoState::fromName).orElse(VideoState.IDLE);
        this.episodeUrl = input.getString(KEY_EPISODE_URL).orElse("");
        this.syncPositionMs = input.getLongOr(KEY_SYNC_POSITION_MS, -1L);
        this.episodeIndex = input.getIntOr(KEY_EPISODE_INDEX, 1);
        this.roadIndex = input.getIntOr(KEY_ROAD_INDEX, 0);
        this.episodeData = input.getString(KEY_EPISODE_DATA).orElse("");
        this.playingTitle = input.getString(KEY_PLAYING_TITLE).orElse("");
        this.watchingPlayers = input.getString(KEY_WATCHING_PLAYERS).orElse("");
        this.playbackPaused = input.getBooleanOr(KEY_PLAYBACK_PAUSED, false);
        this.skinBlock = input.getString(KEY_SKIN_BLOCK).orElse("");
        // 畸形 ScreenId（损坏存档/旧版残留）回落 null，走 lazy 重新生成路径而非中断区块加载
        this.screenId = input.getString(KEY_SCREEN_ID)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return UUID.fromString(s);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .orElse(null);
        this.offsetX = input.getFloatOr(KEY_OFFSET_X, 0.0f);
        this.offsetY = input.getFloatOr(KEY_OFFSET_Y, 0.0f);
        this.offsetZ = input.getFloatOr(KEY_OFFSET_Z, 0.0f);
        this.connectedSpeakers.clear();
        decodeConnectedSpeakers(input.getString(KEY_CONNECTED_SPEAKERS).orElse(""), connectedSpeakers);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putFloat(KEY_SCREEN_WIDTH, screenWidth);
        output.putFloat(KEY_SCREEN_HEIGHT, screenHeight);
        output.putString(KEY_FACING, facing.getName());
        output.putString(KEY_VIDEO_STATE, videoState.name());
        output.putString(KEY_EPISODE_URL, episodeUrl);
        output.putLong(KEY_SYNC_POSITION_MS, syncPositionMs);
        output.putInt(KEY_EPISODE_INDEX, episodeIndex);
        output.putInt(KEY_ROAD_INDEX, roadIndex);
        output.putString(KEY_EPISODE_DATA, episodeData);
        output.putString(KEY_PLAYING_TITLE, playingTitle);
        output.putString(KEY_WATCHING_PLAYERS, watchingPlayers);
        output.putBoolean(KEY_PLAYBACK_PAUSED, playbackPaused);
        output.putString(KEY_SKIN_BLOCK, skinBlock);
        output.putString(KEY_SCREEN_ID, screenId != null ? screenId.toString() : "");
        output.putFloat(KEY_OFFSET_X, offsetX);
        output.putFloat(KEY_OFFSET_Y, offsetY);
        output.putFloat(KEY_OFFSET_Z, offsetZ);
        output.putString(KEY_CONNECTED_SPEAKERS, encodeConnectedSpeakers(connectedSpeakers));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat(KEY_SCREEN_WIDTH, screenWidth);
        tag.putFloat(KEY_SCREEN_HEIGHT, screenHeight);
        tag.putString(KEY_FACING, facing.getName());
        tag.putString(KEY_VIDEO_STATE, videoState.name());
        tag.putString(KEY_EPISODE_URL, episodeUrl);
        tag.putLong(KEY_SYNC_POSITION_MS, syncPositionMs);
        tag.putInt(KEY_EPISODE_INDEX, episodeIndex);
        tag.putInt(KEY_ROAD_INDEX, roadIndex);
        tag.putString(KEY_EPISODE_DATA, episodeData);
        tag.putString(KEY_PLAYING_TITLE, playingTitle);
        tag.putString(KEY_WATCHING_PLAYERS, watchingPlayers);
        tag.putBoolean(KEY_PLAYBACK_PAUSED, playbackPaused);
        tag.putString(KEY_SKIN_BLOCK, skinBlock);
        tag.putString(KEY_SCREEN_ID, screenId != null ? screenId.toString() : "");
        tag.putFloat(KEY_OFFSET_X, offsetX);
        tag.putFloat(KEY_OFFSET_Y, offsetY);
        tag.putFloat(KEY_OFFSET_Z, offsetZ);
        tag.putString(KEY_CONNECTED_SPEAKERS, encodeConnectedSpeakers(connectedSpeakers));
        return tag;
    }

    // ---- ConnectedSpeakers 编解码（磁盘 NBT 与同步 getUpdateTag 共用） ----

    /** 编码为 "x,y,z;x,y,z;..." */
    private static String encodeConnectedSpeakers(List<BlockPos> speakers) {
        var sb = new StringBuilder();
        for (var pos : speakers) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        return sb.toString();
    }

    /** 解码 "x,y,z;x,y,z;..."，格式非法的项静默跳过 */
    private static void decodeConnectedSpeakers(String str, List<BlockPos> into) {
        if (str == null || str.isEmpty()) return;
        for (String s : str.split(";")) {
            if (s.isBlank()) continue;
            String[] p = s.split(",");
            if (p.length == 3) {
                try {
                    into.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // 服务端：屏幕被破坏时清理 SyncGroup + 通知所有已连接音响
        if (level != null && !level.isClientSide()) {
            if (screenId != null) {
                ScreenRemovalListeners.dispatch(screenId, worldPosition);
            }
            // 通知音响清空连接
            for (BlockPos spkPos : new ArrayList<>(connectedSpeakers)) {
                var be = level.getBlockEntity(spkPos);
                if (be instanceof me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity spk) {
                    spk.clearLink();
                }
            }
            connectedSpeakers.clear();
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
