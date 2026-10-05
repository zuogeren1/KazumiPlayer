package me.zuogeren.kazumiplayer.client.danmaku;

/**
 * 弹幕来源标签：独立开关与渲染差异化的归因依据。
 */
public enum DanmakuSource {
    /** B 站视频片内时间轴弹幕 */
    BILIBILI_VIDEO,
    /** B 站直播间实时弹幕 */
    BILIBILI_LIVE,
    /** 房间内玩家聊天互发弹幕 */
    ROOM_CHAT
}
