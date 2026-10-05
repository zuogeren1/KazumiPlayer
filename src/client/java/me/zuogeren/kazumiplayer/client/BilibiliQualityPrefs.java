package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.bilibili.BilibiliApi;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每屏 B 站清晰度偏好与最近一次解析得到的档位表（纯客户端、仅本端生效）。
 * 播放器 GUI 的「清晰度」下拉读取 {@link #info(BlockPos)}，切换后写 {@link #setPreferredQn}，
 * 由调度器重启本屏播放时带上新档位重新解析。
 */
public final class BilibiliQualityPrefs {

    /** 最近一次解析结果：可用档位与当前采用档位 */
    public record Info(List<BilibiliApi.Quality> qualities, int currentQn) {}

    private static final Map<BlockPos, Integer> preferred = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Info> infos = new ConcurrentHashMap<>();

    private BilibiliQualityPrefs() {}

    /** 本屏偏好档位；0 表示未指定（用接口默认档） */
    public static int preferredQn(BlockPos pos) {
        return preferred.getOrDefault(pos, 0);
    }

    public static void setPreferredQn(BlockPos pos, int qn) {
        if (qn <= 0) {
            preferred.remove(pos);
        } else {
            preferred.put(pos, qn);
        }
    }

    public static Info info(BlockPos pos) {
        return infos.get(pos);
    }

    public static void setInfo(BlockPos pos, Info info) {
        infos.put(pos, info);
    }

    /** 换片/停止时清理（档位表随内容失效，偏好保留由调用方决定是否一并清除） */
    public static void clear(BlockPos pos) {
        infos.remove(pos);
        preferred.remove(pos);
    }
}
