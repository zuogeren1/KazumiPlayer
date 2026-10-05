package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;

import java.util.List;

/**
 * 滚动/逆向弹幕的连续纵向打包（世界层与 HUD 层共用同一实现，两层同输入逐帧同输出）。
 *
 * <p>纵向不是「车道 × 车道高」的整行网格：每条待入场条目在 [显示带上缘, 可用下界 − 条目高]
 * 内取一个连续落点，只要求它与「共存期间可能发生水平重叠」的在途条目保持至少
 * {@link #MIN_SEP_PX} 像素的纵向间隔，故同一显示带里能排下多少条由条目自身高度决定
 * （相邻两行间距 = 上一条文本框高 + 间隔），不同字号的条目可以互相嵌入对方的剩余空间，
 * 也不会再因为「整行被占用」而丢掉本来放得下的条目。
 *
 * <p>文本框高 = 字形行高（{@link DanmakuWorldLayer#GLYPH_HEIGHT_PX} = 9）× 该条缩放 q，
 * 与车道高（9 × 1.4 × danmakuFontScale）无关——车道高仍只作为固定弹幕的槽位高与单条缩放上限
 * （见 {@link DanmakuWorldLayer.LaneGeometry}）存在。
 *
 * <p>水平判据保持原占用感知判据（{@link #released}）：同向条目「已推进像素 ≥
 * max(新条绘制宽, 旧条绘制宽) + {@link #MIN_GAP_PX}」（换算成时间提前量、毫秒向上取整）
 * 即视为已让出，可与新条处于同一纵向位置；未让出者视为共存期间可能水平重叠，必须纵向分离。
 * 逆向与滚动互为镜像、两者在中途必然交会，故异向滚动条目一律视为可能水平重叠。
 * 固定弹幕（TOP/BOTTOM）自成一带、压在滚动项之上（B 站语义），不参与滚动项的碰撞集。
 *
 * <p>候选落点由在途条目的边界推导：0（显示带上缘）与每条在途条目的「下让位边界 = 该条下缘 + 间隔」，
 * 取其中最小的可行者，不做固定步长扫描。没有可行落点时的分支：社交条目先按行程进度降序试算
 * 「移除某条视频片内条目后能否腾出落点」，确实腾出才腾位（不白丢视频条目）；压叠开启
 * （danmakuAllowOverlap 或密度档位=OVERLAP）时取重叠量最小的落点压叠，关闭时丢弃（no-free-lane）。
 *
 * <p>间隔取 max({@link #MIN_SEP_PX}, {@link #OVERHANG_LOCAL} × (两条缩放之和))：前者是文本框之间的
 * 最小间隔，后者保证投影/描边这 1 个局部像素的外扩在相邻两条之间也不相交；房间互发弹幕的
 * 空心文字框再按 {@link #FRAME_PAD_PX} 各加一份，故框与框之间同样不相交。
 *
 * <p>准入路径零分配：暂存数组按需扩容后复用，排序是基本类型数组上的原地插入排序，
 * 落点结果由调用方按需构造在屏记录。
 */
final class DanmakuPack {

    /** 两条弹幕文本框之间的最小纵向间隔（显示区像素） */
    static final float MIN_SEP_PX = 2.0f;
    /** 投影/描边在文本框之外的外扩（局部像素）：1 局部像素在该条缩放下等于 q 个显示区像素 */
    static final float OVERHANG_LOCAL = 1.0f;
    /** 房间互发弹幕空心文字框的内边距（显示区像素）：框在字形外接框之外再外扩这么多 */
    static final float FRAME_PAD_PX = 2.0f;
    /** 水平让位判据的间隔（像素）：在途条目已推进像素须 ≥ max(新条,旧条)绘制宽 + 此值才让出纵向位置 */
    static final float MIN_GAP_PX = 6.0f;
    /** 浮点误差兜底（显示区像素） */
    private static final float EPS = 1.0e-4f;
    /** 每个禁用区间在暂存数组里的跨度：落点下界、上界、在途条目盒子上缘、盒子高、间隔 */
    private static final int STRIDE = 5;

    /**
     * 在途条目视图：打包只读这些量，不依赖两层各自的在屏记录类型。
     * 两层的实现必须给出同一口径的值（同一输入逐帧同输出）。
     */
    interface Item {

        /** 该条模式 */
        DanmakuMode mode();

        /** 该条来源（片内弹幕用播放位置驱动、社交弹幕用单调钟） */
        DanmakuSource source();

        /** 该条驱动时钟在准入时刻的取值 */
        long startMs();

        /** 文本框上缘（显示区坐标：0=显示带上缘、向下为正）；仅滚动/逆向条目有效 */
        float top();

        /** 文本框高（显示区像素）= 字形行高 × 该条缩放 */
        float height();

        /** 该条缩放 q = danmakuFontScale × 字号百分比（钳制在单条车道高以内） */
        float scale();

        /** 该条实际绘制宽（显示区像素，已含缩放） */
        float width();
    }

    /**
     * 落点结果。
     *
     * @param top        落点上缘（显示区坐标）；&lt; 0 表示无落点（丢弃该条）
     * @param evictIndex 需先移除的在途条目下标（-1 表示不腾位）
     * @param forced     压叠落点：该条与在途条目纵向重叠（社交条目恒可压叠，视频条目仅在压叠模式）
     */
    record Pick(float top, int evictIndex, boolean forced) {

        static final Pick NONE = new Pick(-1.0f, -1, false);
    }

    /** 禁用区间暂存（每 {@link #STRIDE} 个 float 一条：落点下界、上界、盒子上下缘与间隔、间隔） */
    private float[] blockers = new float[0];
    /** 压叠落点候选（不是每次准入都用到，按需扩容后复用） */
    private float[] overlapTops = new float[0];
    /** 腾位试算候选（在途条目下标）与其行程进度 */
    private int[] victims = new int[0];
    private float[] victimProgress = new float[0];
    /** 腾位试算命中的落点（{@link #evictTrial} 的出参，避免额外对象） */
    private float evictTop;

    /** 尺寸变化收口：把已缓存的落点上缘收进 [0, 可用下界 − 条目高]（几何每帧重算，无其它尺寸相关缓存） */
    static float refitTop(float top, float height, float bottomLimit) {
        return Math.max(0.0f, Math.min(top, bottomLimit - height));
    }

    /** 条目驱动时钟的已流逝量：片内弹幕用播放位置、社交弹幕用单调钟（暂停期前者天然冻结） */
    static long elapsedOf(Item item, long monoMs, long videoMs) {
        return (item.source() == DanmakuSource.BILIBILI_VIDEO ? videoMs : monoMs) - item.startMs();
    }

    /**
     * 水平让位判据（占用感知）：在途条目已推进像素 ≥ max(新条绘制宽, 该条绘制宽) + MIN_GAP 即视为已让出。
     * 占位宽度取两者较大值——更宽的追随弹幕行程更快会追上较窄的前车，只用前车宽度算提前量会在最坏时刻追尾；
     * 提前量按整毫秒向上取整，故不会早放行一毫秒。
     *
     * @param active    在途条目
     * @param newWidth  待入场条目的实际绘制宽（显示区像素，已含缩放）
     * @param screenW   显示区宽（世界层 2×半宽像素、HUD 层画面宽）
     * @param travelMs  滚动行程
     * @param elapsedMs 在途条目已流逝时间（{@link #elapsedOf}）
     */
    static boolean released(Item active, float newWidth, float screenW, long travelMs, long elapsedMs) {
        float widest = Math.max(newWidth, active.width());
        float span = screenW + widest;
        long advance = (long) Math.ceil(travelMs * Math.min(1.0, (widest + MIN_GAP_PX) / span));
        return elapsedMs >= advance;
    }

    /**
     * 连续纵向放置：为待入场条目求一个落点。
     *
     * @param actives      在途条目（两层各自的列表）
     * @param mode         该条模式（SCROLL/REVERSE）
     * @param source       该条来源：决定社交语义（非片内条目可抢占腾位、恒可压叠）与文字框外扩
     * @param newWidth     该条实际绘制宽（显示区像素）
     * @param newHeight    该条文本框高（显示区像素）
     * @param newScale     该条缩放 q
     * @param screenW      显示区宽（显示区像素）
     * @param bottomLimit  可用下界（显示区像素，已扣除底部 UI 安全区与字形外扩）
     * @param travelMs     滚动行程（毫秒）
     * @param monoMs       本帧单调钟毫秒
     * @param videoMs      本帧播放位置毫秒
     * @param allowOverlap 压叠开关：视频片内条目无落点时是否压叠上屏
     * @return 落点；{@code top < 0} 表示丢弃该条
     */
    Pick place(List<? extends Item> actives, DanmakuMode mode, DanmakuSource source, float newWidth,
               float newHeight, float newScale, float screenW, float bottomLimit, long travelMs,
               long monoMs, long videoMs, boolean allowOverlap) {
        float maxTop = bottomLimit - newHeight;
        if (maxTop < 0.0f) return Pick.NONE;
        boolean social = source != DanmakuSource.BILIBILI_VIDEO;
        float newFrame = frameInset(source);
        int count = collect(actives, mode, newWidth, newHeight, newScale, newFrame, screenW, travelMs,
            monoMs, videoMs, -1);
        float top = firstFree(count, maxTop);
        if (top >= 0.0f) return new Pick(top, -1, false);
        if (social) {
            int victim = evictTrial(actives, mode, newWidth, newHeight, newScale, newFrame, screenW,
                bottomLimit, travelMs, monoMs, videoMs);
            if (victim >= 0) return new Pick(evictTop, victim, false);
        }
        if (!social && !allowOverlap) return Pick.NONE;
        // 腾位试算会以「忽略某条」的口径重填暂存，压叠落点必须按完整碰撞集重新收集
        count = collect(actives, mode, newWidth, newHeight, newScale, newFrame, screenW, travelMs,
            monoMs, videoMs, -1);
        top = leastOverlapTop(count, newHeight, maxTop);
        return top < 0.0f ? Pick.NONE : new Pick(top, -1, true);
    }

    /** 该来源条目在文本框之外额外占用的纵向装饰：房间互发的空心文字框 */
    private static float frameInset(DanmakuSource source) {
        return source == DanmakuSource.ROOM_CHAT ? FRAME_PAD_PX : 0.0f;
    }

    /**
     * 收集禁用区间：与「共存期间可能水平重叠」的在途条目相关的落点区间
     * （开区间：端点本身允许，故相切位置可用），并记录条目盒子与间隔供压叠试算。
     *
     * @param skip 试算腾位时忽略的在途条目下标（-1 表示不忽略）
     * @return 禁用区间条数
     */
    private int collect(List<? extends Item> actives, DanmakuMode mode, float newWidth,
                        float newHeight, float newScale, float newFrame, float screenW, long travelMs,
                        long monoMs, long videoMs, int skip) {
        int size = actives.size();
        if (blockers.length < size * STRIDE) blockers = new float[Math.max(STRIDE * 16, size * STRIDE)];
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (i == skip) continue;
            Item item = actives.get(i);
            DanmakuMode itemMode = item.mode();
            boolean conflict;
            if (itemMode == mode) {
                conflict = !released(item, newWidth, screenW, travelMs, elapsedOf(item, monoMs, videoMs));
            } else if ((itemMode == DanmakuMode.SCROLL && mode == DanmakuMode.REVERSE)
                || (itemMode == DanmakuMode.REVERSE && mode == DanmakuMode.SCROLL)) {
                conflict = true;
            } else {
                continue;
            }
            if (!conflict) continue;
            float gap = Math.max(MIN_SEP_PX, OVERHANG_LOCAL * (newScale + item.scale()))
                + newFrame + frameInset(item.source());
            float boxTop = item.top();
            float boxHeight = item.height();
            int base = count * STRIDE;
            blockers[base] = boxTop - newHeight - gap;
            blockers[base + 1] = boxTop + boxHeight + gap;
            blockers[base + 2] = boxTop;
            blockers[base + 3] = boxHeight;
            blockers[base + 4] = gap;
            count++;
        }
        return count;
    }

    /**
     * 自显示带上缘向下找首个可行落点：禁用区间按落点下界升序扫一遍——落点落在某个禁区之内就抬到该禁区上界，
     * 落点已在禁区下界或之下即结束（后续禁区下界只会更大，故此时落点已可通行）。
     * 禁区是开区间、端点本身允许，故抬到上界不会跳过可用的相切位置。
     *
     * @param maxTop 落点上缘上限 = 可用下界 − 文本框高
     * @return 落点上缘；&lt; 0 表示可用域内没有可行落点
     */
    private float firstFree(int count, float maxTop) {
        if (count == 0) return 0.0f;
        sortByLow(count);
        float top = 0.0f;
        for (int i = 0; i < count; i++) {
            int base = i * STRIDE;
            float lo = blockers[base];
            float hi = blockers[base + 1];
            if (top <= lo) break;
            if (top >= hi) continue;
            top = hi;
        }
        if (top > maxTop + EPS) return -1.0f;
        return Math.min(top, maxTop);
    }

    /** 禁用区间按落点下界升序原地插入排序（条数少、且与上一帧高度相关，近似有序） */
    private void sortByLow(int count) {
        for (int i = 1; i < count; i++) {
            int base = i * STRIDE;
            float lo = blockers[base];
            float hi = blockers[base + 1];
            float boxTop = blockers[base + 2];
            float boxHeight = blockers[base + 3];
            float gap = blockers[base + 4];
            int j = i - 1;
            while (j >= 0 && blockers[j * STRIDE] > lo) {
                int to = (j + 1) * STRIDE;
                int from = j * STRIDE;
                blockers[to] = blockers[from];
                blockers[to + 1] = blockers[from + 1];
                blockers[to + 2] = blockers[from + 2];
                blockers[to + 3] = blockers[from + 3];
                blockers[to + 4] = blockers[from + 4];
                j--;
            }
            int at = (j + 1) * STRIDE;
            blockers[at] = lo;
            blockers[at + 1] = hi;
            blockers[at + 2] = boxTop;
            blockers[at + 3] = boxHeight;
            blockers[at + 4] = gap;
        }
    }

    /**
     * 社交条目腾位试算：按行程进度降序（最接近离场者先试）逐个视频片内条目试算「移除它之后是否有落点」，
     * 命中才腾位——不成立的候选不白丢视频条目。
     *
     * @return 需移除的在途条目下标（落点写入 {@link #evictTop}）；-1 表示腾不出落点
     */
    private int evictTrial(List<? extends Item> actives, DanmakuMode mode, float newWidth,
                           float newHeight, float newScale, float newFrame, float screenW,
                           float bottomLimit, long travelMs, long monoMs, long videoMs) {
        int size = actives.size();
        if (victims.length < size) {
            victims = new int[Math.max(8, size)];
            victimProgress = new float[Math.max(8, size)];
        }
        int candidates = 0;
        for (int i = 0; i < size; i++) {
            Item item = actives.get(i);
            if (item.mode() != mode || item.source() != DanmakuSource.BILIBILI_VIDEO) continue;
            victims[candidates] = i;
            victimProgress[candidates] = elapsedOf(item, monoMs, videoMs) / (float) travelMs;
            candidates++;
        }
        for (int i = 1; i < candidates; i++) {
            int index = victims[i];
            float progress = victimProgress[i];
            int j = i - 1;
            while (j >= 0 && victimProgress[j] < progress) {
                victims[j + 1] = victims[j];
                victimProgress[j + 1] = victimProgress[j];
                j--;
            }
            victims[j + 1] = index;
            victimProgress[j + 1] = progress;
        }
        float maxTop = bottomLimit - newHeight;
        for (int i = 0; i < candidates; i++) {
            int count = collect(actives, mode, newWidth, newHeight, newScale, newFrame, screenW,
                travelMs, monoMs, videoMs, victims[i]);
            float top = firstFree(count, maxTop);
            if (top >= 0.0f) {
                evictTop = top;
                return victims[i];
            }
        }
        return -1;
    }

    /**
     * 压叠落点：在候选落点里取与在途条目纵向重叠量（各条重叠长度之和）最小者，并列取更靠上的落点。
     * 候选 = 可用域两端 + 各在途条目盒子的上/下缘（重叠量随落点线性变化，最小值必在这些折点上）。
     */
    private float leastOverlapTop(int count, float newHeight, float maxTop) {
        int size = count * 2 + 2;
        if (overlapTops.length < size) overlapTops = new float[Math.max(16, size)];
        int candidates = 0;
        overlapTops[candidates++] = 0.0f;
        overlapTops[candidates++] = maxTop;
        for (int i = 0; i < count; i++) {
            float boxTop = blockers[i * STRIDE + 2];
            float boxHeight = blockers[i * STRIDE + 3];
            overlapTops[candidates++] = Math.max(0.0f, Math.min(maxTop, boxTop - newHeight));
            overlapTops[candidates++] = Math.max(0.0f, Math.min(maxTop, boxTop + boxHeight));
        }
        float bestTop = -1.0f;
        float bestOverlap = Float.MAX_VALUE;
        for (int c = 0; c < candidates; c++) {
            float top = overlapTops[c];
            float overlap = 0.0f;
            for (int i = 0; i < count; i++) {
                float boxTop = blockers[i * STRIDE + 2];
                float boxBottom = boxTop + blockers[i * STRIDE + 3];
                overlap += Math.max(0.0f,
                    Math.min(top + newHeight, boxBottom) - Math.max(top, boxTop));
            }
            if (overlap < bestOverlap - EPS || (overlap <= bestOverlap + EPS && top < bestTop)) {
                bestOverlap = overlap;
                bestTop = top;
            }
        }
        return bestTop;
    }
}
