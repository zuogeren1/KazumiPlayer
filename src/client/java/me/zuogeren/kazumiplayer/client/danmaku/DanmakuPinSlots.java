package me.zuogeren.kazumiplayer.client.danmaku;

/**
 * 固定弹幕（TOP/BOTTOM）槽位分配器（纯逻辑，世界层与 HUD 层共用同一实现，保证两层口径逐帧一致）。
 *
 * <p>槽位数由显示带几何推导——等于车道数（槽位高即车道高），见
 * {@link DanmakuWorldLayer.LaneGeometry#pinSlots()}：显示带越高、字号越小则可用行数越多，
 * 不存在写死的行数上限。
 *
 * <p>分配规则：
 * <ol>
 *   <li><b>有空槽必占空槽</b>：自轮转游标起环状找第一个空槽，故只要还有空行就绝不压叠；</li>
 *   <li><b>槽位用尽才压叠，且压叠槽位轮转分散</b>：按游标依次落到不同槽位，
 *       不再固定挑「最接近释放」的同一槽（旧实现会让溢出条目全部堆在同一行）。</li>
 * </ol>
 *
 * <p>游标是该类唯一状态：同一输入序列必然给出同一分配结果，故两层同输入逐帧同输出；
 * 槽位数变化（屏幕尺寸/显示区/字号变化）时取模自适应，越界游标自动回到范围内。
 *
 * <p>许可：本文件为本项目原创实现，思路参考 ImmediatelyFast（LGPL-3.0）对文本批次的处理，
 * 但无代码移植。
 */
final class DanmakuPinSlots {

    /** 轮转游标：下一次分配尝试的起始槽位 */
    private int cursor;

    /**
     * 分配结果。
     *
     * @param slot   槽位下标；-1 表示无槽可用（槽位数为 0）
     * @param forced true=槽位已用尽，本条与既有占用者压叠（调用方按来源与 allowOverlap 决定是否接受）
     */
    record Pick(int slot, boolean forced) {
        private static final Pick NONE = new Pick(-1, false);
    }

    /** 复位（断线/卸载世界时随所属层一起 reset） */
    void reset() {
        cursor = 0;
    }

    /**
     * 分配一个槽位：优先空槽（自游标起环状扫描），无空槽时按游标轮转给出压叠槽位。
     *
     * @param used 各槽占用情况（长度 = 槽位数）
     * @return 分配结果；{@code slot &lt; 0} 表示无槽可用
     */
    Pick allocate(boolean[] used) {
        int slots = used.length;
        if (slots <= 0) return Pick.NONE;
        for (int offset = 0; offset < slots; offset++) {
            int slot = Math.floorMod(cursor + offset, slots);
            if (!used[slot]) {
                cursor = Math.floorMod(slot + 1, slots);
                return new Pick(slot, false);
            }
        }
        int slot = Math.floorMod(cursor, slots);
        cursor = Math.floorMod(cursor + 1, slots);
        return new Pick(slot, true);
    }
}
