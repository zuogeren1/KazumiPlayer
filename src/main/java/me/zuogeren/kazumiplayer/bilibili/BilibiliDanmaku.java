package me.zuogeren.kazumiplayer.bilibili;

import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;

/**
 * B 站弹幕条目：视频片内轨（protobuf 分段接口）与直播间实时弹幕（WebSocket）共用的中间模型。
 *
 * @param text            弹幕文本
 * @param timeMs          出现时刻（毫秒，相对视频起点；直播来源无时间轴，恒 0）
 * @param mode            显示模式
 * @param colorRgb        RGB 颜色（B 站 24 位色值）
 * @param fontSizePercent 字号百分比（相对基准字号）
 */
public record BilibiliDanmaku(String text, long timeMs, DanmakuMode mode, int colorRgb, int fontSizePercent) {

    /** 弹幕模式编号 → 显示模式：4 底部、5 顶部，其余（含 1/2/3 滚动与未知值）一律滚动 */
    public static DanmakuMode modeOf(int rawMode) {
        return switch (rawMode) {
            case 4 -> DanmakuMode.BOTTOM;
            case 5 -> DanmakuMode.TOP;
            default -> DanmakuMode.SCROLL;
        };
    }

    /** 字号编号 → 百分比：18 小、36 大，其余（含 25 标准与未知值）一律基准字号 */
    public static int fontSizePercentOf(int rawFontSize) {
        return switch (rawFontSize) {
            case 18 -> 72;
            case 36 -> 144;
            default -> 100;
        };
    }
}
