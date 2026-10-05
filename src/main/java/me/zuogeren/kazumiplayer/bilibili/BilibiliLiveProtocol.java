package me.zuogeren.kazumiplayer.bilibili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 直播间弹幕 WebSocket 协议编解码：16 字节包头（总长/头长/协议版本/操作码/序号）+ 报文体。
 * 认证声明 protover=2，服务端下发的 op5 压缩报文即 zlib（版本 2）；解压后仍是多个完整包首尾相接，
 * 故递归拆包后再与明文包一并以「版本 0/1 + 操作码 + 原文」的形式交给调用方。
 * 版本 3（brotli）JDK 无内置解码器，本项目固定 protover=2，遇到即跳过并记 DEBUG。
 */
final class BilibiliLiveProtocol {

    static final int OP_HEARTBEAT = 2;
    static final int OP_HEARTBEAT_REPLY = 3;
    static final int OP_MESSAGE = 5;
    static final int OP_AUTH = 7;
    static final int OP_AUTH_REPLY = 8;

    static final int VERSION_PLAIN = 0;
    static final int VERSION_INT32 = 1;

    /** 认证回复无法解析时的 code 值（与真实业务 code 区分：不据此重连） */
    static final int AUTH_REPLY_UNPARSED = -1;

    private static final int VERSION_ZLIB = 2;
    private static final int VERSION_BROTLI = 3;
    private static final int HEADER_BYTES = 16;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    /** 拆包结果：version 为 0（JSON 文本）或 1（心跳回复的 int32），payload 为解压后的原文 */
    record Packet(int version, int operation, byte[] payload) {}

    /** 认证回复：code 为 0 表示认证通过 */
    record AuthReply(int code) {}

    private BilibiliLiveProtocol() {}

    /** 心跳报文体：服务端不校验内容，与网页端保持同样的占位串 */
    static final byte[] HEARTBEAT_BODY = "[object Object]".getBytes(StandardCharsets.UTF_8);

    /** 组包：认证与心跳包的协议版本恒为 1 */
    static byte[] encode(int operation, byte[] body) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + body.length);
        buffer.putInt(HEADER_BYTES + body.length);
        buffer.putShort((short) HEADER_BYTES);
        buffer.putShort((short) VERSION_INT32);
        buffer.putInt(operation);
        buffer.putInt(1);
        buffer.put(body);
        return buffer.array();
    }

    static byte[] authBody(long roomId, String token) {
        JsonObject auth = new JsonObject();
        auth.addProperty("uid", 0);
        auth.addProperty("roomid", roomId);
        auth.addProperty("protover", 2);
        auth.addProperty("platform", "web");
        auth.addProperty("type", 2);
        auth.addProperty("key", token);
        return auth.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<Packet> decode(byte[] frame) {
        List<Packet> packets = new ArrayList<>();
        collect(frame, packets, 0);
        return packets;
    }

    private static void collect(byte[] data, List<Packet> packets, int depth) {
        int pos = 0;
        while (pos + HEADER_BYTES <= data.length) {
            ByteBuffer header = ByteBuffer.wrap(data, pos, data.length - pos);
            int total = header.getInt();
            int headerLength = header.getShort() & 0xFFFF;
            int version = header.getShort() & 0xFFFF;
            int operation = header.getInt();
            if (total < HEADER_BYTES || headerLength < HEADER_BYTES || pos + total > data.length) {
                KazumiLog.danmaku.debug("[bilibili] malformed live packet (total={}, header={}, offset={})",
                    total, headerLength, pos);
                return;
            }
            byte[] body = Arrays.copyOfRange(data, pos + headerLength, pos + total);
            if (version == VERSION_ZLIB) {
                expand(body, packets, depth);
            } else if (version == VERSION_BROTLI) {
                KazumiLog.danmaku.debug("[bilibili] live packet version 3 (brotli) unsupported, dropped");
            } else {
                packets.add(new Packet(version, operation, body));
            }
            pos += total;
        }
        if (pos != data.length) {
            KazumiLog.danmaku.debug("[bilibili] live frame trailing {} bytes ignored", data.length - pos);
        }
    }

    private static void expand(byte[] body, List<Packet> packets, int depth) {
        if (depth >= MAX_DEPTH) {
            KazumiLog.danmaku.debug("[bilibili] live packet nesting too deep, dropped");
            return;
        }
        byte[] plain = zlibDecompress(body);
        if (plain != null) collect(plain, packets, depth + 1);
    }

    private static byte[] zlibDecompress(byte[] body) {
        try {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(body)));
        } catch (IOException wrapped) {
            try {
                return readAll(new InflaterInputStream(new ByteArrayInputStream(body), new Inflater(true)));
            } catch (IOException raw) {
                KazumiLog.danmaku.warn("[bilibili] live packet inflate failed: {}", raw.getMessage());
                return null;
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_PAYLOAD_BYTES) break;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    static AuthReply parseAuthReply(byte[] payload) {
        JsonObject root = parseJson(payload);
        if (root == null) return new AuthReply(AUTH_REPLY_UNPARSED);
        return new AuthReply(asInt(root.get("code"), AUTH_REPLY_UNPARSED));
    }

    /** 心跳回复：协议版本 1 的 4 字节人气值；解析不出时返回 -1 */
    static int parsePopularity(byte[] payload) {
        if (payload.length < 4) return -1;
        return ByteBuffer.wrap(payload, 0, 4).getInt();
    }

    /** 取消息的 cmd 字段；结构异常时返回 null（仅用于失败日志，不参与流程判断） */
    static String commandOf(byte[] payload) {
        JsonObject root = parseJson(payload);
        return root == null ? null : asString(root.get("cmd"));
    }

    /**
     * 解析一条 DANMU_MSG。直播消息形态多样（表情弹幕、活动弹幕、脏数据），
     * 任何结构缺失都按"跳过这一条"处理并返回 null，绝不抛异常——异常逃出会让整条 WS 会话静默断流。
     */
    static BilibiliDanmaku parseDanmaku(byte[] payload) {
        JsonObject root = parseJson(payload);
        if (root == null) return null;
        String cmd = asString(root.get("cmd"));
        if (cmd == null || !cmd.startsWith("DANMU_MSG")) return null;
        JsonArray info = asArray(root.get("info"));
        if (info == null || info.size() < 3) return null;
        JsonArray meta = asArray(info.get(0));
        if (meta == null || meta.size() < 5) return null;
        String text = asString(info.get(1));
        if (text == null || text.isBlank()) return null;
        if (isEmote(meta)) return null;
        int mode = asInt(meta.get(1), 0);
        int fontSize = asInt(meta.get(2), 0);
        int color = asInt(meta.get(3), 0xFFFFFF);
        return new BilibiliDanmaku(text, 0L, BilibiliDanmaku.modeOf(mode), color & 0xFFFFFF,
            BilibiliDanmaku.fontSizePercentOf(fontSize));
    }

    /** 表情弹幕：info[0][12]=1，或二次编码 extra 里的 dm_type=1（extra 解析失败按普通弹幕处理） */
    private static boolean isEmote(JsonArray meta) {
        if (asInt(meta.size() > 12 ? meta.get(12) : null, 0) == 1) return true;
        if (meta.size() <= 15) return false;
        JsonObject holder = asObject(meta.get(15));
        if (holder == null) return false;
        String extra = asString(holder.get("extra"));
        if (extra == null || extra.isBlank()) return false;
        JsonObject parsed;
        try {
            parsed = JsonUtil.GSON.fromJson(extra, JsonObject.class);
        } catch (RuntimeException e) {
            return false;
        }
        return asInt(parsed == null ? null : parsed.get("dm_type"), 0) == 1;
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray asArray(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String asString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            ? element.getAsString() : null;
    }

    private static int asInt(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static JsonObject parseJson(byte[] payload) {
        if (payload.length == 0) return null;
        try {
            return JsonUtil.GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            KazumiLog.danmaku.debug("[bilibili] live payload json failed: {}", e.getMessage());
            return null;
        }
    }
}
