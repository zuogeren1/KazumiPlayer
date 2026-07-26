package me.zuogeren.kazumiplayer.speaker;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.client.PlayStateListener;
import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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

import java.util.UUID;

public class SpeakerBlockEntity extends BlockEntity implements PlayStateListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    // 连接状态
    private UUID linkedScreenId;
    private BlockPos linkedScreenPos;

    // 客户端：音频播放器（纯音频，ALEngine）
    private WaterMediaPlayer audioPlayer;
    private int driftTickCounter;
    private static final int DRIFT_CHECK_INTERVAL = 100; // 5 秒 @20tps
    private static final long DRIFT_THRESHOLD_MS = 500;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(SpeakerRegistration.SPEAKER_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getLinkedScreenId() { return linkedScreenId; }
    public BlockPos getLinkedScreenPos() { return linkedScreenPos; }

    public void setLink(UUID screenId, BlockPos screenPos) {
        this.linkedScreenId = screenId;
        this.linkedScreenPos = screenPos;
        markDirty();
    }

    public void clearLink() {
        this.linkedScreenId = null;
        this.linkedScreenPos = null;
        markDirty();
    }

    public boolean isLinked() {
        return linkedScreenId != null && linkedScreenPos != null;
    }

    // ---- 客户端：音频播放管理 ----

    /** 由外部（ConnectionTool/连接建立后）调用来启动音频播放器 */
    public void startAudio(String videoUrl, WaterMediaPlayer screenPlayer) {
        if (audioPlayer != null) return;
        audioPlayer = new WaterMediaPlayer();
        screenPlayer.addListener(this);
        audioPlayer.addListener(this); // 也需要监听自己的停止事件做清理
    }

    public void stopAudio() {
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
    }

    public void clientTick() {
        if (level == null || !level.isClientSide()) return;
        if (!isLinked()) return;

        // 检查屏幕 BE 是否存在（双向判断：存在→取消静音，不存在→静音）
        VideoScreenBlockEntity screen = getLinkedScreen();
        if (audioPlayer != null) {
            audioPlayer.getPlayer().mute(screen == null);
        }

        // 漂移校正
        if (audioPlayer == null || screen == null || screen.player == null) return;
        if (!audioPlayer.isPlaying()) return;

        driftTickCounter++;
        if (driftTickCounter >= DRIFT_CHECK_INTERVAL) {
            driftTickCounter = 0;
            // 暂停时不校正（两者都不动）
            if (screen.isPlaybackPaused()) return;
            long screenTime = screen.player.getTimeMs();
            long speakerTime = audioPlayer.getTimeMs();
            if (Math.abs(screenTime - speakerTime) > DRIFT_THRESHOLD_MS) {
                audioPlayer.seek(screenTime);
                LOGGER.debug("Speaker drift corrected: {}ms → {}ms", speakerTime, screenTime);
            }
        }
    }

    @Nullable
    private VideoScreenBlockEntity getLinkedScreen() {
        if (level == null || linkedScreenPos == null) return null;
        var be = level.getBlockEntity(linkedScreenPos);
        if (be instanceof VideoScreenBlockEntity screen
                && linkedScreenId != null
                && linkedScreenId.equals(screen.getScreenId())) {
            return screen;
        }
        return null;
    }

    // ---- PlayStateListener ----

    @Override
    public void onPause() {
        if (audioPlayer != null) audioPlayer.pause();
    }

    @Override
    public void onResume() {
        if (audioPlayer != null) {
            audioPlayer.resume();
            driftTickCounter = 0; // 恢复后重置漂移计数器
        }
    }

    @Override
    public void onSeek(long positionMs) {
        if (audioPlayer != null) audioPlayer.seek(positionMs);
    }

    @Override
    public void onStop() {
        stopAudio();
    }

    // ---- NBT ----

    @Override
    protected void loadAdditional(ValueInput input) {
        this.linkedScreenId = input.getString("LinkedScreenId")
            .filter(s -> !s.isEmpty()).map(UUID::fromString).orElse(null);
        this.linkedScreenPos = input.read("LinkedScreenPos", net.minecraft.core.BlockPos.CODEC).orElse(null);
        // 防御性检查：清除无效连接
        if (linkedScreenId != null || linkedScreenPos != null) {
            if (level != null && !level.isClientSide() && getLinkedScreen() == null) {
                clearLink();
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putString("LinkedScreenId", linkedScreenId != null ? linkedScreenId.toString() : "");
        if (linkedScreenPos != null) {
            output.store("LinkedScreenPos", net.minecraft.core.BlockPos.CODEC, linkedScreenPos);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("LinkedScreenId", linkedScreenId != null ? linkedScreenId.toString() : "");
        if (linkedScreenPos != null) {
            tag.putInt("LinkedScreenX", linkedScreenPos.getX());
            tag.putInt("LinkedScreenY", linkedScreenPos.getY());
            tag.putInt("LinkedScreenZ", linkedScreenPos.getZ());
        }
        return tag;
    }

    private void markDirty() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            stopAudio();
        }
        // 服务端：通知屏幕清理反向索引
        if (level != null && !level.isClientSide() && isLinked()) {
            var be = getLinkedScreen();
            if (be instanceof VideoScreenBlockEntity screen) {
                screen.removeConnectedSpeaker(worldPosition);
            }
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
