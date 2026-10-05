package me.zuogeren.kazumiplayer.network.packet;

/**
 * 弹幕显示模式。聊天栏互发来源恒 SCROLL（产品语义：房内互发为滚动社交弹幕）；
 * 其余取值供 B 站时间轴弹幕分带使用，非协议限制。
 */
public enum DanmakuMode {
    /** 右进左出滚动 */
    SCROLL,
    /** 顶部居中驻留 */
    TOP,
    /** 底部居中驻留 */
    BOTTOM,
    /** 左进右出逆向滚动（B 站 mode 6） */
    REVERSE,
    /** 高级弹幕（B 站 mode 7/8/9：定位/代码/BAS）：数据模型无定位与代码字段，渲染层跳过 */
    ADVANCED
}
