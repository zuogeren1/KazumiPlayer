package me.zuogeren.kazumiplayer.playback.source;

/**
 * 视频源类型（对齐 Kazumi lib/services/video_source/video_source_service.dart VideoSourceType）
 */
public enum VideoSourceType {
    /** 在线解析（WebView/MCEF 嗅探） */
    ONLINE,
    /** 本地缓存 */
    CACHED
}
