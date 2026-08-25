package me.zuogeren.kazumiplayer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分类日志工具，每个分类使用独立 logger 名（kazumiplayer.<分类>），
 * 可在配置中按分类开启/关闭 DEBUG 级别日志（见 {@link me.zuogeren.kazumiplayer.LogConfig}）。
 *
 * 使用: KazumiLog.playback.info("message {}", arg);
 */
public final class KazumiLog {
    private KazumiLog() {}

    /** Mod 生命周期 / 通用 */
    public static final Logger general   = LoggerFactory.getLogger("kazumiplayer");

    /** 播放引擎 (WaterMediaPlayer, VideoSourceResolver) */
    public static final Logger playback  = LoggerFactory.getLogger("kazumiplayer.playback");

    /** 视频嗅探与视频源解析 (playback/source 包) */
    public static final Logger sniff     = LoggerFactory.getLogger("kazumiplayer.sniff");

    /** 渲染 (VideoScreenRenderer, VideoScreenTexture) */
    public static final Logger render    = LoggerFactory.getLogger("kazumiplayer.render");

    /** 屏幕方块实体 (VideoScreenBlockEntity) */
    public static final Logger screen    = LoggerFactory.getLogger("kazumiplayer.screen");

    /** 音响系统 (SpeakerBlockEntity) */
    public static final Logger speaker   = LoggerFactory.getLogger("kazumiplayer.speaker");

    /** 音频播放 (SpeakerClientAudio, 音响音频跟随/漂移校正等) */
    public static final Logger audio     = LoggerFactory.getLogger("kazumiplayer.audio");

    /** 网络包 (所有 Packet 类) */
    public static final Logger network   = LoggerFactory.getLogger("kazumiplayer.network");

    /** 同步组 (SyncGroupManager) */
    public static final Logger sync      = LoggerFactory.getLogger("kazumiplayer.sync");

    /** 命令 (PlayCommands, SearchCommands 等) */
    public static final Logger command   = LoggerFactory.getLogger("kazumiplayer.command");

    /** 搜索 (BangumiApi, SearchManager) */
    public static final Logger search    = LoggerFactory.getLogger("kazumiplayer.search");

    /** 规则引擎 (RuleEngine, RuleDownloader, strategies) */
    public static final Logger rule      = LoggerFactory.getLogger("kazumiplayer.rule");

    /** HTTP 请求 (HttpUtil) */
    public static final Logger http      = LoggerFactory.getLogger("kazumiplayer.http");

    /** 弹幕 (DanmakuRoomManager, DanmakuWorldLayer, ClientDanmakuStore) */
    public static final Logger danmaku   = LoggerFactory.getLogger("kazumiplayer.danmaku");
}
