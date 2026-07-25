package me.zuogeren.kazumiplayer.playback;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.screen.VideoState;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端播放流程编排
 * 搜索缓存条目 -> 查询章节 -> 获取剧集URL -> MCEF嗅探 -> WaterMedia播放
 */
public class PlaybackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final WaterMediaPlayer waterMedia = new WaterMediaPlayer();

    /**
     * 通过规则和源URL直接播放 (Phase 5: 跳过嗅探，直接播放URL)
     */
    public CompletableFuture<Void> playUrl(VideoScreenBlockEntity screen, String videoUrl) {
        screen.setVideoState(VideoState.LOADING);
        return CompletableFuture.runAsync(() -> {
            Minecraft.getInstance().execute(() -> {
                waterMedia.play(videoUrl);
                screen.setVideoState(VideoState.PLAYING);
            });
        });
    }

    /**
     * 通过规则引擎搜索并播放
     * 1. 查询章节 -> 获取剧集URL列表
     * 2. 选择指定集数的URL
     * 3. Phase 5: 直接播放URL (Phase 5.1将加入MCEF嗅探)
     */
    public CompletableFuture<Void> play(RuleEngine engine, Rule rule, String source,
                                         VideoScreenBlockEntity screen, int episode) {
        screen.setVideoState(VideoState.LOADING);
        return engine.queryChapters(rule, source)
            .thenCompose(result -> {
                if (result.roads().isEmpty()) {
                    screen.setVideoState(VideoState.ERROR);
                    LOGGER.warn("No roads found for {} via {}", source, rule.getName());
                    return CompletableFuture.failedFuture(
                            new RuntimeException("未找到剧集列表"));
                }
                // 使用第一条线路
                Road road = result.roads().get(0);
                int idx = Math.max(0, Math.min(episode - 1, road.data().size() - 1));
                String episodeUrl = road.data().get(idx);
                LOGGER.info("Playing episode {} from {}: {}", episode, rule.getName(), episodeUrl);

                // Phase 5: 直接播放URL (后续Phase加入MCEF嗅探)
                Minecraft.getInstance().execute(() -> {
                    waterMedia.play(episodeUrl);
                    screen.setVideoState(VideoState.PLAYING);
                });
                return CompletableFuture.<Void>completedFuture(null);
            });
    }

    public void stop(VideoScreenBlockEntity screen) {
        waterMedia.stop();
        screen.setVideoState(VideoState.STOPPED);
    }

    public WaterMediaPlayer getWaterMedia() { return waterMedia; }
}
