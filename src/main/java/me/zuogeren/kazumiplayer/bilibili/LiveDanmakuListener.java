package me.zuogeren.kazumiplayer.bilibili;

/**
 * 直播弹幕会话回调。onDanmaku 由连接线程调用（非 MC 主线程），上屏前需自行切回主线程；
 * 只有 onDanmaku 是每帧必做的，另两个按需覆写即可（可写成 lambda）。
 *
 * <p>回调次数约定：onError 每次连接/握手失败各一次（重连期间可能多次）；
 * onClosed 仅在会话彻底结束时一次（重连次数耗尽或连接关闭且不再重连）；
 * 调用方主动 close() 不再回调 onClosed。
 */
public interface LiveDanmakuListener {

    /** 收到一条弹幕（已过滤非弹幕消息） */
    void onDanmaku(BilibiliDanmaku danmaku);

    /** 会话结束 */
    default void onClosed(String reason) {}

    /** 单次连接失败 */
    default void onError(String message) {}
}
