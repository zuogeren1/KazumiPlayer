package me.zuogeren.kazumiplayer.network.packet;

/**
 * 弹幕显示模式。聊天栏互发来源恒 SCROLL（产品语义：房内互发为滚动社交弹幕）；
 * TOP/BOTTOM 供 dandanplay 片内轨分道使用，非协议限制。
 */
public enum DanmakuMode {
    SCROLL,
    TOP,
    BOTTOM
}
