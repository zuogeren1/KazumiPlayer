package me.zuogeren.kazumiplayer.screen;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

public class VideoScreenBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();

    private float screenWidth = 3.0f;
    private float screenHeight = 2.0f;
    private Direction facing = Direction.NORTH;
    private VideoState videoState = VideoState.IDLE;

    // 播放状态
    private String episodeUrl = "";
    private long syncPositionMs = -1;

    // 集数管理
    private int episodeIndex = 1;          // 当前集数 (1-based)
    private String episodeData = "";       // Road JSON（所有集的名称+URL）
    private String watchingPlayers = "";   // 观看者 UUID 列表，逗号分隔
    private boolean playbackPaused;
    private String skinBlock = "";         // 方块皮肤 ID，空=默认
    private UUID screenId;                 // 屏幕唯一标识，lazy 生成

    // 客户端暂存，不持久化
    public transient me.zuogeren.kazumiplayer.playback.WaterMediaPlayer player;
    private transient String lastEpisodeUrl;
    public transient long lastAppliedPosition = -1;
    public transient long playbackStartedAt; // 防抖：上次启动播放的时间戳
    public transient boolean endedNotified;

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
    public String getEpisodeData() { return episodeData; }
    public String getWatchingPlayers() { return watchingPlayers; }
    public boolean isPlaybackPaused() { return playbackPaused; }
    public String getSkinBlock() { return skinBlock; }
    /** 屏幕唯一标识，首次访问时 lazy 生成 */
    public UUID getScreenId() {
        if (screenId == null) {
            screenId = UUID.randomUUID();
            markDirty();
        }
        return screenId;
    }
    /** 客户端：检测 episodeUrl 是否刚发生变化（用于检测切换集数） */
    public boolean justChanged(String url) { return !url.equals(lastEpisodeUrl); }
    public void markSeen(String url) { this.lastEpisodeUrl = url; }

    public void setScreenSize(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
        markDirty();
    }

    public void setFacing(Direction facing) {
        this.facing = facing;
        markDirty();
    }

    public void setVideoState(VideoState state) {
        this.videoState = state;
        markDirty();
    }

    public void setPlayback(String url, long positionMs) {
        this.episodeUrl = url;
        this.syncPositionMs = positionMs;
        this.videoState = VideoState.PLAYING;
        markDirty();
        LOGGER.info("setPlayback url={} posMs={} side={}", url, positionMs,
            level != null && level.isClientSide() ? "client" : "server");
    }

    /** 设置完整播放信息（含集数和 Road 数据） */
    public void setPlaybackFull(String url, long positionMs, int episodeIdx, String episodeDataJson) {
        this.episodeUrl = url;
        this.syncPositionMs = positionMs;
        this.episodeIndex = episodeIdx;
        this.episodeData = episodeDataJson;
        this.videoState = VideoState.PLAYING;
        markDirty();
    }

    public void clearPlayback() {
        this.episodeUrl = "";
        this.syncPositionMs = -1;
        this.episodeIndex = 1;
        this.episodeData = "";
        this.videoState = VideoState.IDLE;
        if (player != null) {
            player.stop();
            player = null;
        }
        markDirty();
        LOGGER.info("clearPlayback side={}",
            level != null && level.isClientSide() ? "client" : "server");
    }

    public void updateSyncPosition(long positionMs) {
        this.syncPositionMs = positionMs;
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
        this.screenWidth = input.getFloatOr("ScreenWidth", 3.0f);
        this.screenHeight = input.getFloatOr("ScreenHeight", 2.0f);
        this.facing = input.getString("Facing").map(Direction::byName).orElse(Direction.NORTH);
        this.videoState = input.getString("VideoState").map(VideoState::fromName).orElse(VideoState.IDLE);
        this.episodeUrl = input.getString("EpisodeUrl").orElse("");
        this.syncPositionMs = input.getLongOr("SyncPositionMs", -1L);
        this.episodeIndex = input.getIntOr("EpisodeIndex", 1);
        this.episodeData = input.getString("EpisodeData").orElse("");
        this.watchingPlayers = input.getString("WatchingPlayers").orElse("");
        this.playbackPaused = input.getBooleanOr("PlaybackPaused", false);
        this.skinBlock = input.getString("SkinBlock").orElse("");
        this.screenId = input.getString("ScreenId").map(UUID::fromString).orElse(null);

        if (level != null && level.isClientSide() && episodeUrl.isEmpty() && player != null) {
            player.stop();
            player = null;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putFloat("ScreenWidth", screenWidth);
        output.putFloat("ScreenHeight", screenHeight);
        output.putString("Facing", facing.getName());
        output.putString("VideoState", videoState.name());
        output.putString("EpisodeUrl", episodeUrl);
        output.putLong("SyncPositionMs", syncPositionMs);
        output.putInt("EpisodeIndex", episodeIndex);
        output.putString("EpisodeData", episodeData);
        output.putString("WatchingPlayers", watchingPlayers);
        output.putBoolean("PlaybackPaused", playbackPaused);
        output.putString("SkinBlock", skinBlock);
        output.putString("ScreenId", screenId != null ? screenId.toString() : "");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("ScreenWidth", screenWidth);
        tag.putFloat("ScreenHeight", screenHeight);
        tag.putString("Facing", facing.getName());
        tag.putString("VideoState", videoState.name());
        tag.putString("EpisodeUrl", episodeUrl);
        tag.putLong("SyncPositionMs", syncPositionMs);
        tag.putInt("EpisodeIndex", episodeIndex);
        tag.putString("EpisodeData", episodeData);
        tag.putString("WatchingPlayers", watchingPlayers);
        tag.putBoolean("PlaybackPaused", playbackPaused);
        tag.putString("SkinBlock", skinBlock);
        tag.putString("ScreenId", screenId != null ? screenId.toString() : "");
        return tag;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // 服务端：屏幕被破坏时清理 SyncGroup
        if (screenId != null && level != null && !level.isClientSide()) {
            me.zuogeren.kazumiplayer.sync.SyncGroupManager.get().leaveByScreenId(screenId);
        }
        if (player != null && level != null && level.isClientSide()) {
            player.stop();
            player = null;
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
