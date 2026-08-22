package me.zuogeren.kazumiplayer.util;

/**
 * 进程内单调毫秒钟（System.nanoTime 换算）。
 *
 * 同步协议的时间戳统一使用单调钟而非墙钟：墙钟会被 NTP/手动校时跳变，
 * 且客户端与服务器墙钟的固有偏差会直接转化为播放位置误差；
 * 单调钟差值运算稳定，跨机器的对齐交给时钟同步握手完成。
 */
public final class MonoClock {

    private MonoClock() {}

    /** 本进程启动以来的毫秒数（可能为负，仅用于差值运算） */
    public static long millis() {
        return System.nanoTime() / 1_000_000;
    }
}
