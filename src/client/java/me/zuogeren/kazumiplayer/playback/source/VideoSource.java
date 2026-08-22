package me.zuogeren.kazumiplayer.playback.source;

/**
 * 视频源解析结果（对齐 Kazumi VideoSource 类）。
 * 蓝本中的 offset（续播偏移）由本项目的服务端同步 seek 机制承担，不再冗余携带。
 *
 * @param url    视频 URL (M3U8/MP4/本地路径)
 * @param type   视频源类型
 * @param format 解析时确认的媒体格式提示
 */
public record VideoSource(String url, VideoSourceType type, VideoSourceFormat format) {

    public static VideoSource online(String url, VideoSourceFormat format) {
        return new VideoSource(url, VideoSourceType.ONLINE, format);
    }

    @Override
    public String toString() {
        return "VideoSource(url: " + url + ", type: " + type + ", format: " + format + ")";
    }
}
