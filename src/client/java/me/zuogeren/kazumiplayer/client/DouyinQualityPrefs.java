package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.bilibili.BilibiliApi;
import me.zuogeren.kazumiplayer.bilibili.DouyinApi;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每屏抖音清晰度偏好与最近一次解析得到的档位表（纯客户端、仅本端生效）。
 *
 * <p>档位表存 {@link BilibiliApi.Quality} 而非抖音自有类型：GUI 的「清晰度」下拉是通用组件，
 * 抖音档位经 {@link DouyinApi.Quality#toGuiQuality()} 适配后（flags 恒 0，无大会员概念）
 * 可直接复用同一条下拉链路，无需在界面层再写一套。
 *
 * <p>与 {@link BilibiliQualityPrefs} 分开存的原因：两家档位编号体系不同——
 * B 站用官方 qn（16/32/64/80/116…，越大越清晰），抖音没有官方 qn，
 * 本模组按码率降序自编 1..N（<b>1 为最高档</b>）。混用会让「偏好 qn」在换源时指向错误档位。
 */
public final class DouyinQualityPrefs {

    /** 最近一次解析结果：可用档位与当前采用档位 */
    public record Info(List<BilibiliApi.Quality> qualities, int currentQn) {}

    private static final Map<BlockPos, Integer> preferred = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Info> infos = new ConcurrentHashMap<>();

    private DouyinQualityPrefs() {}

    /** 本屏偏好档位；0 表示未指定（取最高档） */
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

    /** 该屏当前生效档位名；无档位表（非抖音源/尚未解析）时返回空串 */
    public static String currentLabel(BlockPos pos) {
        Info info = infos.get(pos);
        if (info == null) return "";
        for (BilibiliApi.Quality q : info.qualities()) {
            if (q.qn() == info.currentQn()) return q.label();
        }
        return info.currentQn() > 0 ? String.valueOf(info.currentQn()) : "";
    }

    public static void setInfo(BlockPos pos, Info info) {
        infos.put(pos, info);
    }

    /** 换源时清档位表（偏好保留）：档位名只对产生它的那次解析有效 */
    public static void clearInfo(BlockPos pos) {
        infos.remove(pos);
    }

    /** 换片/停止时清理（档位表与偏好一并清除） */
    public static void clear(BlockPos pos) {
        infos.remove(pos);
        preferred.remove(pos);
    }
}
