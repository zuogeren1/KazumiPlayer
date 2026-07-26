package me.zuogeren.kazumiplayer.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 分类日志工具，后续可按分类开启/关闭调试日志。
 *
 * 使用: KazumiLog.playback.info("message {}", arg);
 */
public final class KazumiLog {
    private KazumiLog() {}

    /** Mod 生命周期 / 通用 */
    public static final Logger general   = LogUtils.getLogger();

    /** 播放引擎 (WaterMediaPlayer, PlaybackManager) */
    public static final Logger playback  = LogUtils.getLogger();

    /** 视频嗅探 (VideoSniffer) */
    public static final Logger sniff     = LogUtils.getLogger();

    /** 渲染 (VideoScreenRenderer, VideoScreenTexture) */
    public static final Logger render    = LogUtils.getLogger();

    /** 屏幕方块实体 (VideoScreenBlockEntity) */
    public static final Logger screen    = LogUtils.getLogger();

    /** 音响系统 (SpeakerBlockEntity) */
    public static final Logger speaker   = LogUtils.getLogger();

    /** 网络包 (所有 Packet 类) */
    public static final Logger network   = LogUtils.getLogger();

    /** 同步组 (SyncGroupManager) */
    public static final Logger sync      = LogUtils.getLogger();

    /** 命令 (PlayCommands, SearchCommands 等) */
    public static final Logger command   = LogUtils.getLogger();

    /** 搜索 (BangumiApi, SearchManager) */
    public static final Logger search    = LogUtils.getLogger();

    /** 规则引擎 (RuleEngine, RuleDownloader, strategies) */
    public static final Logger rule      = LogUtils.getLogger();

    /** HTTP 请求 (HttpUtil) */
    public static final Logger http      = LogUtils.getLogger();
}
