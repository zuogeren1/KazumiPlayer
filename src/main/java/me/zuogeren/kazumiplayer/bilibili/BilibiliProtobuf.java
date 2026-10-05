package me.zuogeren.kazumiplayer.bilibili;

import java.nio.charset.StandardCharsets;

/**
 * 最小 protobuf 读取器：只解析 B 站弹幕响应用到的 varint 与 length-delimited 字段，
 * 未知字段按 wire type 跳过（定长字段直接略过字节）。
 */
final class BilibiliProtobuf {

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH = 2;
    private static final int WIRE_FIXED32 = 5;

    private final byte[] data;
    private final int end;
    private int pos;
    private int fieldNumber;
    private int wireType;
    private long varintValue;
    private int valueStart;
    private int valueLength;

    private BilibiliProtobuf(byte[] data, int start, int end) {
        this.data = data;
        this.pos = start;
        this.end = end;
    }

    BilibiliProtobuf(byte[] data) {
        this(data, 0, data.length);
    }

    /** 读取下一个字段；返回 false 表示已到末尾。数据畸形时抛 IllegalStateException */
    boolean next() {
        if (pos >= end) return false;
        long key = readVarint();
        fieldNumber = (int) (key >>> 3);
        wireType = (int) (key & 7L);
        switch (wireType) {
            case WIRE_VARINT -> varintValue = readVarint();
            case WIRE_FIXED64 -> skip(8);
            case WIRE_LENGTH -> {
                long length = readVarint();
                if (length < 0 || length > end - pos) throw new IllegalStateException("protobuf 长度越界");
                valueStart = pos;
                valueLength = (int) length;
                pos += valueLength;
            }
            case WIRE_FIXED32 -> skip(4);
            default -> throw new IllegalStateException("protobuf wire type 不支持：" + wireType);
        }
        return true;
    }

    int fieldNumber() {
        return fieldNumber;
    }

    int wireType() {
        return wireType;
    }

    boolean isVarint() {
        return wireType == WIRE_VARINT;
    }

    boolean isLengthDelimited() {
        return wireType == WIRE_LENGTH;
    }

    long varint() {
        return varintValue;
    }

    /** varint 字段按 32 位有符号解读（弹幕的时间/模式/字号/颜色都是 int32/uint32） */
    int intValue() {
        return (int) varintValue;
    }

    /** length-delimited 字段按 UTF-8 文本解读 */
    String string() {
        return new String(data, valueStart, valueLength, StandardCharsets.UTF_8);
    }

    /** length-delimited 字段作为嵌套消息继续解析 */
    BilibiliProtobuf message() {
        return new BilibiliProtobuf(data, valueStart, valueStart + valueLength);
    }

    private long readVarint() {
        long result = 0L;
        int shift = 0;
        while (shift < 64) {
            if (pos >= end) throw new IllegalStateException("protobuf 数据截断");
            int b = data[pos++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IllegalStateException("protobuf varint 过长");
    }

    private void skip(int count) {
        if (count < 0 || pos + count > end) throw new IllegalStateException("protobuf 数据截断");
        pos += count;
    }
}
