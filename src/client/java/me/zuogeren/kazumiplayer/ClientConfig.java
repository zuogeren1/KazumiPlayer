package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class ClientConfig {
    public static final ClientConfig CONFIG;
    public static final ModConfigSpec SPEC;

    // 视频默认音量 (0.0 - 1.0)，与原版唱片机滑块相乘
    public final ModConfigSpec.DoubleValue videoVolume;
    // 最大同时嗅探数
    public final ModConfigSpec.IntValue maxConcurrentSniffs;
    // 每个客户端最大同时播放数
    public final ModConfigSpec.IntValue maxConcurrentPlays;
    // 嗅探超时 (秒)
    public final ModConfigSpec.IntValue sniffTimeoutSeconds;
    // 全屏观影画面覆盖窗口的百分比 (1-100)
    public final ModConfigSpec.IntValue fullscreenCoverage;
    // 全屏观影画面不透明度百分比 (10-100)
    public final ModConfigSpec.IntValue fullscreenOpacity;
    // 屏幕比例与视频不一致时的播放画面适配方式
    public final ModConfigSpec.EnumValue<VideoFit> videoFit;
    // 视频拉流方式（流式快速起播 / 磁盘缓存首播需下完）
    public final ModConfigSpec.EnumValue<VideoCacheMode> videoCacheMode;
    // 未播放时在世界中显示屏幕面位置预览框（便于调整屏幕）
    public final ModConfigSpec.BooleanValue showIdleScreenFrame;
    // 显示弹幕开关
    public final ModConfigSpec.BooleanValue danmakuEnabled;
    // 弹幕整体不透明度
    public final ModConfigSpec.DoubleValue danmakuOpacity;
    // 弹幕字号缩放
    public final ModConfigSpec.DoubleValue danmakuFontScale;
    // 弹幕滚动速度倍率
    public final ModConfigSpec.DoubleValue danmakuSpeedMultiplier;
    // 弹幕显示区域占屏幕高度比例
    public final ModConfigSpec.DoubleValue danmakuAreaRatio;
    // 单屏同屏最大弹幕条数
    public final ModConfigSpec.IntValue danmakuMaxOnScreen;
    // 全屏观影时显示弹幕
    public final ModConfigSpec.BooleanValue danmakuShowInFullscreen;
    // 自动加载 B 站视频片内时间轴弹幕
    public final ModConfigSpec.BooleanValue danmakuBilibiliVideo;
    // 自动连接 B 站直播间实时弹幕
    public final ModConfigSpec.BooleanValue danmakuBilibiliLive;
    // 显示房间内玩家聊天互发弹幕
    public final ModConfigSpec.BooleanValue danmakuRoomChat;
    // 片内弹幕时间偏移（毫秒）
    public final ModConfigSpec.IntValue danmakuTimeOffsetMs;
    // 显示滚动弹幕
    public final ModConfigSpec.BooleanValue danmakuShowScroll;
    // 显示顶部固定弹幕
    public final ModConfigSpec.BooleanValue danmakuShowTop;
    // 显示底部固定弹幕
    public final ModConfigSpec.BooleanValue danmakuShowBottom;
    // 弹幕文字描边
    public final ModConfigSpec.BooleanValue danmakuOutline;
    // 单屏片内弹幕池上限
    public final ModConfigSpec.IntValue danmakuMaxEntries;
    // B 站登录 Cookie（本地扫码登录或手动粘贴；本地有值时优先于服务端下发）
    public final ModConfigSpec.ConfigValue<String> bilibiliCookie;

    /** 视频拉流方式 */
    public enum VideoCacheMode {
        /** 流式：不落磁盘缓存，FFmpeg 直接边下边播（起播快，停止更容易中断） */
        STREAM,
        /** 磁盘缓存：引擎先把整个文件下完再解码（首次等待久，重复播放秒开） */
        CACHE
    }

    /** 屏幕为非常规比例时视频画面的适配方式 */
    public enum VideoFit {
        /** 拉伸：画面填满整个屏幕面（可能变形） */
        STRETCH,
        /** 等比缩放后居中：保持视频原始宽高比，屏幕面内居中显示（两侧或上下留边） */
        CONTAIN
    }

    private ClientConfig(ModConfigSpec.Builder builder) {
        builder.push("playback");

        videoVolume = builder
                .comment("默认视频音量 (0.0 = 静音, 1.0 = 最大)")
                .defineInRange("videoVolume", 1.0, 0.0, 1.0);

        maxConcurrentSniffs = builder
                .comment("最大同时嗅探数")
                .defineInRange("maxConcurrentSniffs", 3, 1, 10);

        maxConcurrentPlays = builder
                .comment("每个客户端最大同时播放屏幕数")
                .defineInRange("maxConcurrentPlays", 3, 1, 10);

        sniffTimeoutSeconds = builder
                .comment("视频嗅探超时时间 (秒)")
                .defineInRange("sniffTimeoutSeconds", 30, 5, 120);

        fullscreenCoverage = builder
                .comment("全屏观影画面占窗口的百分比 (1-100)，居中显示，小于 100 时四周透出游戏世界")
                .defineInRange("fullscreenCoverage", 100, 1, 100);

        fullscreenOpacity = builder
                .comment("全屏观影画面不透明度百分比 (10-100)")
                .defineInRange("fullscreenOpacity", 100, 10, 100);

        videoFit = builder
                .comment("屏幕比例与视频不一致时的播放行为",
                        "STRETCH - 拉伸填满整个屏幕面（可能变形）",
                        "CONTAIN - 等比缩放后居中（保持视频宽高比，留边）")
                .defineEnum("videoFit", VideoFit.STRETCH);

        videoCacheMode = builder
                .comment("视频拉流方式",
                         "STREAM - 流式：不落磁盘缓存，边下边播，起播快（默认）",
                         "CACHE  - 磁盘缓存：先把整个媒体文件下完再开始解码，大文件首播需等数十秒，重复播放秒开")
                .defineEnum("videoCacheMode", VideoCacheMode.STREAM);

        showIdleScreenFrame = builder
                .comment("未播放时在世界中显示屏幕面位置预览框（便于调整屏幕的位置与大小）")
                .define("showIdleScreenFrame", true);

        builder.pop();
        builder.push("danmaku");

        danmakuEnabled = builder
                .comment("显示弹幕总开关（B 站视频片内弹幕 / 直播间实时弹幕 / 房间玩家互发弹幕）",
                         "关闭后画面上不渲染弹幕层，各来源开关同时失效")
                .define("danmakuEnabled", true);

        danmakuOpacity = builder
                .comment("弹幕整体不透明度 (0.0 - 1.0)")
                .defineInRange("danmakuOpacity", 0.9, 0.1, 1.0);

        danmakuFontScale = builder
                .comment("弹幕字号缩放 (1.0 = 基准字号)")
                .defineInRange("danmakuFontScale", 1.0, 0.5, 3.0);

        danmakuSpeedMultiplier = builder
                .comment("弹幕滚动速度倍率 (越大越快，基准行程 5 秒)")
                .defineInRange("danmakuSpeedMultiplier", 1.0, 0.25, 4.0);

        danmakuAreaRatio = builder
                .comment("弹幕显示区域占屏幕高度的比例")
                .defineInRange("danmakuAreaRatio", 0.5, 0.1, 1.0);

        danmakuMaxOnScreen = builder
                .comment("单屏同屏最大弹幕条数，超出丢弃")
                .defineInRange("danmakuMaxOnScreen", 60, 5, 200);

        danmakuShowInFullscreen = builder
                .comment("全屏观影时在画面上显示弹幕")
                .define("danmakuShowInFullscreen", true);

        danmakuBilibiliVideo = builder
                .comment("播放 B 站视频时自动加载该视频的片内弹幕（按视频时间轴显示）")
                .define("danmakuBilibiliVideo", true);

        danmakuBilibiliLive = builder
                .comment("播放 B 站直播间时自动连接实时弹幕（收到即显示）")
                .define("danmakuBilibiliLive", true);

        danmakuRoomChat = builder
                .comment("显示房间内玩家的聊天互发弹幕")
                .define("danmakuRoomChat", true);

        danmakuTimeOffsetMs = builder
                .comment("片内弹幕时间偏移（毫秒，正值延后显示，用于对齐画面延迟）")
                .defineInRange("danmakuTimeOffsetMs", 0, -60000, 60000);

        danmakuShowScroll = builder
                .comment("显示滚动弹幕")
                .define("danmakuShowScroll", true);

        danmakuShowTop = builder
                .comment("显示顶部固定弹幕")
                .define("danmakuShowTop", true);

        danmakuShowBottom = builder
                .comment("显示底部固定弹幕")
                .define("danmakuShowBottom", true);

        danmakuOutline = builder
                .comment("弹幕文字描边（关闭后仅按弹幕自身颜色绘制）")
                .define("danmakuOutline", true);

        danmakuMaxEntries = builder
                .comment("单屏片内弹幕池上限（超出丢弃时间轴靠后的弹幕，防热门视频占用过多内存）")
                .defineInRange("danmakuMaxEntries", 30000, 1000, 200000);

        builder.pop();
        builder.push("bilibili");

        bilibiliCookie = builder
                .comment("B 站登录 Cookie：可用配置界面「扫码登录」自动获取，或从浏览器开发者工具复制粘贴",
                         "本地填写后优先于服务端下发的凭据；留空时回落到服务端配置（未登录清晰度上限 720P）")
                .define("bilibiliCookie", "");

        builder.pop();
    }

    static {
        Pair<ClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }
}
