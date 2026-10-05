package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;
import me.zuogeren.kazumiplayer.util.MonoClock;

import java.util.UUID;

/**
 * 弹幕统一条目模型（归 Store 所有，渲染线只依赖此结构）。
 *
 * @param text           弹幕文本（聊天栏来源=发言原文不含名字；名字前缀展示归渲染线）
 * @param colorRgb       RGB 颜色（房间互发来源恒 0xFFFFFF）
 * @param mode           显示模式（滚动/顶部/底部）
 * @param fontSizePercent 字号百分比（{@link #FONT_SIZE_STANDARD}=基准字号）
 * @param displayAtMs    到期显示时刻：即时项恒 0（下一帧出队）；片内项 = 弹幕时间 + 时间偏移
 * @param source         来源标签（独立开关与渲染差异化归因）
 * @param senderName     发送者名（nullable；B 站片内弹幕无发送者名）
 * @param senderUuid     发送者 UUID（归因/日志/「隐藏自己的弹幕」过滤预留）
 * @param receivedAtMono 入队时刻（MonoClock 毫秒，审计用）
 */
public record DanmakuEntry(String text, int colorRgb, DanmakuMode mode, int fontSizePercent,
                           long displayAtMs, DanmakuSource source, String senderName, UUID senderUuid,
                           long receivedAtMono) {

    /** 基准字号百分比 */
    public static final int FONT_SIZE_STANDARD = 100;

    /** 房间互发弹幕：即时项，滚动 */
    public static DanmakuEntry roomChat(String text, String senderName, UUID senderUuid, int colorRgb) {
        return new DanmakuEntry(text, colorRgb, DanmakuMode.SCROLL, FONT_SIZE_STANDARD, 0L,
            DanmakuSource.ROOM_CHAT, senderName, senderUuid, MonoClock.millis());
    }

    /** 视频时间轴弹幕：displayAtMs 由入队侧应用时间偏移 */
    public static DanmakuEntry videoTimeline(String text, DanmakuMode mode, int colorRgb,
                                             int fontSizePercent, long displayAtMs) {
        return new DanmakuEntry(text, colorRgb, mode, fontSizePercent, displayAtMs,
            DanmakuSource.BILIBILI_VIDEO, null, null, MonoClock.millis());
    }

    /** 直播间实时弹幕：即时项 */
    public static DanmakuEntry live(String text, DanmakuMode mode, int colorRgb, int fontSizePercent) {
        return new DanmakuEntry(text, colorRgb, mode, fontSizePercent, 0L,
            DanmakuSource.BILIBILI_LIVE, null, null, MonoClock.millis());
    }
}
