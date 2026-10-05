package me.zuogeren.kazumiplayer.bilibili;

/**
 * 直播间弹幕会话句柄：{@link BilibiliApi#openLiveDanmaku} 返回，连接与心跳由实现自行维持。
 * 主动关闭后不再重连、不再有任何回调；重复调用 close() 无副作用。
 */
public interface LiveDanmakuSession extends AutoCloseable {

    /** 关闭会话（幂等，可在任意线程调用） */
    @Override
    void close();

    /** 当前是否处于已连接状态（重连退避期间为 false） */
    boolean isOpen();
}
