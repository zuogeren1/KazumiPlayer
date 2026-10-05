package me.zuogeren.kazumiplayer.bilibili;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频片内弹幕的 protobuf 解析（字段编号取自实测响应）：
 * DmWebViewReply 的分段配置在字段 4（page_size=1 毫秒、total=2 段数上限）、弹幕总数在字段 8；
 * DmSegMobileReply 的弹幕列表是重复字段 1，单条 DanmakuElem 取 1=id、2=出现毫秒、3=模式、
 * 4=字号、5=颜色、7=文本、11=弹幕池、12=idStr。
 */
final class BilibiliDanmakuCodec {

    /** 特殊弹幕池（代码/BAS 类弹幕，文本不是可读文本），取数时过滤 */
    private static final int POOL_SPECIAL = 2;
    /** 默认分段配置：每段 6 分钟、段数上限 100（接口给出的 total 恒为该上限，不是实际段数） */
    private static final long DEFAULT_PAGE_SIZE_MS = 360_000L;
    private static final int DEFAULT_SEGMENT_CAP = 100;

    /** 单条弹幕解析结果；id 仅用于跨段去重，不进入展示模型 */
    record RawEntry(String id, BilibiliDanmaku danmaku) {}

    /** 分段配置 */
    record ViewInfo(long pageSizeMs, int segmentCap, long totalDanmaku) {}

    private BilibiliDanmakuCodec() {}

    static ViewInfo parseView(byte[] bytes) {
        long pageSizeMs = DEFAULT_PAGE_SIZE_MS;
        int segmentCap = DEFAULT_SEGMENT_CAP;
        long total = 0L;
        BilibiliProtobuf reader = new BilibiliProtobuf(bytes);
        while (reader.next()) {
            if (reader.fieldNumber() == 4 && reader.isLengthDelimited()) {
                BilibiliProtobuf config = reader.message();
                while (config.next()) {
                    if (!config.isVarint()) continue;
                    if (config.fieldNumber() == 1) pageSizeMs = config.varint();
                    else if (config.fieldNumber() == 2) segmentCap = config.intValue();
                }
            } else if (reader.fieldNumber() == 8 && reader.isVarint()) {
                total = reader.varint();
            }
        }
        if (pageSizeMs <= 0) pageSizeMs = DEFAULT_PAGE_SIZE_MS;
        segmentCap = Math.max(1, Math.min(segmentCap, 1000));
        return new ViewInfo(pageSizeMs, segmentCap, total);
    }

    static List<RawEntry> parseSegment(byte[] bytes) {
        List<RawEntry> entries = new ArrayList<>();
        BilibiliProtobuf reader = new BilibiliProtobuf(bytes);
        while (reader.next()) {
            if (reader.fieldNumber() != 1 || !reader.isLengthDelimited()) continue;
            RawEntry entry = parseElem(reader.message());
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private static RawEntry parseElem(BilibiliProtobuf reader) {
        long id = 0L;
        String idStr = null;
        long timeMs = 0L;
        int mode = 0;
        int fontSize = 0;
        int color = 0xFFFFFF;
        int pool = 0;
        String text = null;
        while (reader.next()) {
            switch (reader.fieldNumber()) {
                case 1 -> {
                    if (reader.isVarint()) id = reader.varint();
                }
                case 2 -> {
                    if (reader.isVarint()) timeMs = reader.intValue();
                }
                case 3 -> {
                    if (reader.isVarint()) mode = reader.intValue();
                }
                case 4 -> {
                    if (reader.isVarint()) fontSize = reader.intValue();
                }
                case 5 -> {
                    if (reader.isVarint()) color = reader.intValue();
                }
                case 7 -> {
                    if (reader.isLengthDelimited()) text = reader.string();
                }
                case 11 -> {
                    if (reader.isVarint()) pool = reader.intValue();
                }
                case 12 -> {
                    if (reader.isLengthDelimited()) idStr = reader.string();
                }
                default -> {
                }
            }
        }
        if (pool == POOL_SPECIAL) return null;
        if (text == null || text.isBlank()) return null;
        String key = idStr != null && !idStr.isBlank() ? idStr : String.valueOf(id);
        BilibiliDanmaku danmaku = new BilibiliDanmaku(text, timeMs, BilibiliDanmaku.modeOf(mode),
            color & 0xFFFFFF, BilibiliDanmaku.fontSizePercentOf(fontSize));
        return new RawEntry(key, danmaku);
    }
}
