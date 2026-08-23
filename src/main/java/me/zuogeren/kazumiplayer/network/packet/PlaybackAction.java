package me.zuogeren.kazumiplayer.network.packet;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Locale;

/**
 * 播放控制动作（PlaybackControlPacket 的 action 字段）。
 * wire 格式保持与旧字符串协议一致的小写 snake_case。
 *
 * <p>value 字段语义：
 * <ul>
 *   <li>{@link #SEEK_FORWARD}/{@link #SEEK_BACK}：相对秒数</li>
 *   <li>{@link #SEEK_GOTO}：绝对毫秒位置</li>
 *   <li>{@link #NEXT}/{@link #PREV}/{@link #PAUSE}/{@link #RESUME}：忽略 value</li>
 * </ul>
 */
public enum PlaybackAction {
    NEXT,
    PREV,
    PAUSE,
    RESUME,
    SEEK_FORWARD,
    SEEK_BACK,
    SEEK_GOTO;

    public static final StreamCodec<ByteBuf, PlaybackAction> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PlaybackAction decode(ByteBuf buf) {
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new DecoderException("Unknown playback action: " + name);
            }
        }

        @Override
        public void encode(ByteBuf buf, PlaybackAction action) {
            ByteBufCodecs.STRING_UTF8.encode(buf, action.name().toLowerCase(Locale.ROOT));
        }
    };
}
