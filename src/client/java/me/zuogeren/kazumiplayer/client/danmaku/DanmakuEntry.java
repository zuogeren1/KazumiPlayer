package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;

import java.util.UUID;

/**
 * 弹幕统一条目模型（归 Store 所有，渲染线只依赖此结构）。
 *
 * @param text          弹幕文本（聊天栏来源=发言原文不含名字；名字前缀展示归渲染线）
 * @param colorRgb      RGB 颜色（聊天栏来源恒 0xFFFFFF）
 * @param mode          显示模式（聊天栏来源恒 SCROLL）
 * @param displayAtMs   到期显示时刻：即时项恒 0（下一帧出队）；片内项由入队侧应用 timeOffset
 * @param senderName    发送者名（nullable，dandanplay 来源无发送者）
 * @param senderUuid    发送者 UUID（归因/日志/「隐藏自己的弹幕」过滤预留）
 * @param receivedAtMono 入队时刻（MonoClock 毫秒，审计用）
 */
public record DanmakuEntry(String text, int colorRgb, DanmakuMode mode,
                           long displayAtMs, String senderName, UUID senderUuid,
                           long receivedAtMono) {
}
