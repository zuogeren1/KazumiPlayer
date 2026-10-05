package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.network.packet.DanmakuBroadcastPacket;
import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.DirectLinkQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 审计/修复的游戏内验证工具（物品外壳永久保留）。
 *
 * 工作流：每次修复需要游戏内验证时，在 {@link #runSuite} 的【验证区】写入检查调用
 * （实现放【验证实现区】，复用下方输出辅助）。三层手段：
 * <ol>
 *   <li>模拟调用——反射调用服务端权威静态方法/读取私有字段做断言（编译期不破坏分层）</li>
 *   <li>输出直显——把修复后的聊天文案按真实组装路径渲染打印，肉眼确认措辞与颜色</li>
 *   <li>MANUAL——确实需要双端联机/实网/重启才能验的，写入报告供复制</li>
 * </ol>
 * 输出策略：PASS/MANUAL 只进缓冲不打聊天，聊天栏仅显示 FAIL（红）、区块标题、
 * 聊天样本与末尾汇总；末行附「复制完整报告」按钮（CopyToClipboard）一键取全量结果，
 * 避免长篇 DBG 内容污染聊天与日志。提交前把两个区域的内容清空，只保留外壳。
 *
 * <p>分层约束：本类在 common，禁止编译期引用 client/server source set 的类——
 * 跨层目标一律 {@code Class.forName} 字符串反射（运行时客户端 jar 同时含两端代码）。
 */
public class DebugVerifyItem extends Item {

    private static final StringBuilder BUF = new StringBuilder();
    private static int passCount;
    private static int failCount;
    private static int manualCount;

    public DebugVerifyItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) return InteractionResult.SUCCESS;
        runSuite(player, null);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || !ctx.getLevel().isClientSide()) return InteractionResult.PASS;
        runSuite(player, ctx.getClickedPos());
        return InteractionResult.SUCCESS;
    }

    private static void runSuite(Player player, BlockPos clickedPos) {
        BUF.setLength(0);
        passCount = failCount = manualCount = 0;

        line(player, "======== KazumiPlayer DBG ========");
        // ===== 验证区（提交前清空本区域调用，保留外壳）=====

        section("F10 弹幕包结构");
        checkDanmakuPacketShape(player);
        section("F10 Store 契约（模拟调用）");
        checkDanmakuStoreContract(player);
        section("F10 服务端结构存在性");
        checkDanmakuServerShapes(player);
        section("F10 MANUAL 双端/实机项");
        manualDanmakuItems();

        if (clickedPos != null) {
            appendScreenSnapshot(player, clickedPos);
        }

        // ===== 验证区结束 =====

        Component copyButton = Component.literal("[📋 复制完整报告]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.CopyToClipboard(BUF.toString()))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("点击复制全部 DBG 输出"))));
        player.sendSystemMessage(Component.literal(
                "[DBG] 共 " + (passCount + failCount + manualCount) + " 行：PASS " + passCount
                        + " / FAIL " + failCount + " / MANUAL " + manualCount + "  ")
                .withStyle(failCount > 0 ? ChatFormatting.RED : ChatFormatting.GREEN)
                .append(copyButton));
    }

    /** 右键屏幕方块时的附加快照：BE 权威 NBT 与本端播放器状态一览（场景核对用） */
    private static void appendScreenSnapshot(Player player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof VideoScreenBlockEntity be)) {
            gray("屏幕快照：目标不是视频屏幕方块 " + pos.toShortString());
            return;
        }
        buf("[DBG] ---- 屏幕快照 " + pos.toShortString() + " ----");
        buf("[DBG] 集/线路: " + be.getEpisodeIndex() + " (road " + be.getRoadIndex() + ")"
                + ", EpisodeData=" + (be.getEpisodeData().isEmpty() ? "无"
                : (DirectLinkQueue.isQueueData(be.getEpisodeData()) ? "队列" : "剧集"))
                + ", SyncPositionMs=" + be.getSyncPositionMs());
        String url = be.getEpisodeUrl();
        buf("[DBG] EpisodeUrl: " + (url.length() > 60 ? url.substring(0, 60) + "…" : url));
        int watchers = be.getWatchingPlayers().isEmpty()
                ? 0 : be.getWatchingPlayers().split(",").length;
        buf("[DBG] WatchingPlayers: " + watchers + " 人");

        // 本端播放状态经反射读取（ScreenPlayer 在 client 层）
        try {
            Class<?> spm = fqn("me.zuogeren.kazumiplayer.client.ScreenPlayerManager");
            Object sp = spm.getMethod("get", BlockPos.class).invoke(null, pos);
            Class<?> spCls = sp.getClass();
            boolean hasPlayer = spCls.getField("player").get(sp) != null;
            boolean everPlayed = spCls.getField("everPlayed").getBoolean(sp);
            boolean bypassSync = spCls.getField("bypassSync").getBoolean(sp);
            boolean muted = spCls.getField("muted").getBoolean(sp);
            float scale = spCls.getField("volumeScale").getFloat(sp);
            long lastFail = spCls.getField("lastFailedAt").getLong(sp);
            buf("[DBG] 本端: player=" + (hasPlayer ? "在册" : "无")
                    + ", everPlayed=" + everPlayed
                    + ", bypassSync=" + bypassSync
                    + ", 音量=" + Math.round((muted ? 0f : scale) * 100) + "%"
                    + (muted ? "(静音)" : "")
                    + ", 上次失败=" + (lastFail == 0 ? "无" : ((now() - lastFail) / 1000) + "s 前"));
            manualCount += 5;
        } catch (ReflectiveOperationException e) {
            buf("[DBG] 本端状态读取失败: " + e);
            manualCount++;
        }
        player.sendSystemMessage(Component.literal("[DBG] 屏幕快照已写入报告（见复制按钮）")
                .withStyle(ChatFormatting.GRAY));
    }

    // ===== 验证实现区（提交前随验证区一并清空）=====

    /** F10：广播包 wire 形状与模式枚举 */
    private static void checkDanmakuPacketShape(Player player) {
        var comps = DanmakuBroadcastPacket.class.getRecordComponents();
        report(player, "广播包 record 组件数=9", comps != null && comps.length == 9);
        report(player, "组件含 screenPos/screenId/senderUuid/senderName/text/colorRgb/mode",
            comps != null && comps.length == 9
                && "screenPos".equals(comps[0].getName()) && "screenId".equals(comps[1].getName())
                && "positionMs".equals(comps[2].getName()) && "serverTimestamp".equals(comps[3].getName())
                && "senderUuid".equals(comps[4].getName()) && "senderName".equals(comps[5].getName())
                && "text".equals(comps[6].getName()) && "colorRgb".equals(comps[7].getName())
                && "mode".equals(comps[8].getName()));
        try {
            Object type = DanmakuBroadcastPacket.class.getField("TYPE").get(null);
            Object id = type.getClass().getMethod("id").invoke(type);
            report(player, "TYPE id=danmaku_broadcast",
                id != null && id.toString().contains("danmaku_broadcast"));
        } catch (ReflectiveOperationException e) {
            report(player, "TYPE id=danmaku_broadcast", false);
        }
        report(player, "DanmakuMode 三模式且 SCROLL 序 0",
            DanmakuMode.values().length == 3 && DanmakuMode.SCROLL.ordinal() == 0);
    }

    /** F10：Store 契约模拟调用（即时项出队/片内 ε 判定/seek 自检压缩/clear 幂等），反射跨层 */
    private static void checkDanmakuStoreContract(Player player) {
        try {
            Class<?> store = fqn("me.zuogeren.kazumiplayer.client.danmaku.ClientDanmakuStore");
            Class<?> entryCls = fqn("me.zuogeren.kazumiplayer.client.danmaku.DanmakuEntry");
            report(player, "EPSILON_MS=250", store.getField("EPSILON_MS").getLong(null) == 250L);
            report(player, "INSTANT_DISPLAY_MS=5000", store.getField("INSTANT_DISPLAY_MS").getLong(null) == 5000L);
            report(player, "SEEK_DETECT_MS=1500", store.getField("SEEK_DETECT_MS").getLong(null) == 1500L);

            BlockPos pos = new BlockPos(1, 2, 3);
            var roomChat = entryCls.getMethod("roomChat", String.class, String.class, UUID.class, int.class);
            var videoTimeline = entryCls.getMethod("videoTimeline", String.class, DanmakuMode.class,
                int.class, int.class, long.class);
            var enqueue = store.getMethod("enqueue", BlockPos.class, entryCls);
            var enqueueAll = store.getMethod("enqueueAll", BlockPos.class, java.util.List.class);
            var pollDue = store.getMethod("pollDue", BlockPos.class, long.class);
            var components = entryCls.getRecordComponents();
            report(player, "DanmakuEntry 九字段契约", components != null && components.length == 9);
            report(player, "Store 五方法签名齐备", enqueue != null && enqueueAll != null && pollDue != null);

            clearQuietly(store, pos);
            enqueue.invoke(null, pos, roomChat.invoke(null, "instant", null, UUID.randomUUID(), 0xFFFFFF));
            var due = asList(pollDue.invoke(null, pos, 100L));
            report(player, "即时项下一帧出队",
                due.size() == 1 && "instant".equals(entryText(entryCls, due.get(0))));

            enqueue.invoke(null, pos, videoTimeline.invoke(null,
                "later", DanmakuMode.SCROLL, 0xFFFFFF, 100, 50000L));
            var notDue = asList(pollDue.invoke(null, pos, 100L));
            var dueInTime = asList(pollDue.invoke(null, pos, 50100L));
            report(player, "片内项 ε 容差判定",
                notDue.isEmpty() && dueInTime.size() == 1);

            enqueue.invoke(null, pos, videoTimeline.invoke(null,
                "stale", DanmakuMode.SCROLL, 0xFFFFFF, 100, 1000L));
            pollDue.invoke(null, pos, 100L);
            pollDue.invoke(null, pos, 50000L);
            var afterSeek = asList(pollDue.invoke(null, pos, 50100L));
            report(player, "seek 自检压缩过期残留", afterSeek.isEmpty());

            clearQuietly(store, pos);
            report(player, "clear 后队列为空",
                asList(pollDue.invoke(null, pos, 99999L)).isEmpty());
        } catch (ReflectiveOperationException e) {
            fail(player, "Store 契约反射失败: " + e);
        }
    }

    /** F10：服务端监听器与旁路池结构存在性（按方法名探测，避免编译期类引用） */
    private static void checkDanmakuServerShapes(Player player) {
        try {
            Class<?> listener = fqn("me.zuogeren.kazumiplayer.server.danmaku.DanmakuChatListener");
            boolean hasChatHook = false;
            for (var m : listener.getDeclaredMethods()) {
                if (m.getName().equals("onServerChat")) hasChatHook = true;
            }
            report(player, "ServerChatEvent 监听器存在", hasChatHook);

            Class<?> mgr = fqn("me.zuogeren.kazumiplayer.server.danmaku.DanmakuRoomManager");
            boolean hasAppend = false;
            boolean hasClear = false;
            for (var m : mgr.getMethods()) {
                if (m.getName().equals("append")) hasAppend = true;
                if (m.getName().equals("clear")) hasClear = true;
            }
            report(player, "旁路池 append/clear 存在", hasAppend && hasClear);

            Class<?> sm = fqn("me.zuogeren.kazumiplayer.sync.SyncGroupManager");
            boolean hasFind = false;
            for (var m : sm.getMethods()) {
                if (m.getName().equals("findGroupByPlayer")) hasFind = true;
            }
            report(player, "SyncGroupManager.findGroupByPlayer 存在", hasFind);
        } catch (ReflectiveOperationException e) {
            fail(player, "服务端结构反射失败: " + e);
        }
    }

    /** F10 MANUAL：确需双端联机/实机的回归指引，随报告带走 */
    private static void manualDanmakuItems() {
        gray("F10：双端同屏观看，一端聊天栏发言——另一端画面弹幕「名字：内容」滚过且聊天栏照常显示（双重可见为预期）");
        gray("F10：非观看玩家的聊天不产生任何弹幕；同一玩家连发第二条约 1s 冷却后才上屏");
        gray("F10：全屏观影两路径（GUI 全屏按钮 / 观影器物品）弹幕均显示；退出世界重进无残留弹幕");
        gray("F10：直播直链屏互发弹幕照常显示（不依赖时间同步）；换集/停止后旧弹幕清空");
    }

    private static void clearQuietly(Class<?> store, BlockPos pos) throws ReflectiveOperationException {
        store.getMethod("clear", BlockPos.class).invoke(null, pos);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object obj) {
        return (List<Object>) obj;
    }

    private static String entryText(Class<?> entryCls, Object entry) throws ReflectiveOperationException {
        return (String) entryCls.getMethod("text").invoke(entry);
    }

    // ===== 验证实现区结束 =====

    // ---- 输出辅助（外壳保留）：FAIL 即时红显，其余进缓冲由末尾复制按钮携带 ----

    /** 跨 source set 目标的类加载（common 禁编译期引用 client/server 类） */
    private static Class<?> fqn(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void section(String title) {
        BUF.append("[DBG] ---- ").append(title).append(" ----\n");
    }

    private static void report(Player player, String label, boolean ok) {
        String text = "[DBG] " + label + ": " + (ok ? "PASS" : "FAIL");
        buf(text);
        if (ok) passCount++;
        else {
            failCount++;
            player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED));
        }
    }

    private static void fail(Player player, String text) {
        buf("[DBG] " + text);
        failCount++;
        player.sendSystemMessage(Component.literal("[DBG] " + text).withStyle(ChatFormatting.RED));
    }

    private static void line(Player player, String text) {
        buf(text);
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
    }

    private static void gray(String text) {
        buf("[DBG] " + text);
        manualCount++;
    }

    private static void sample(Player player, String label, String rendered) {
        report(player, label + " 无 key 泄漏", !rendered.contains("kazumiplayer."));
        String text = "[样本] " + label + " → " + rendered;
        buf(text);
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    private static void buf(String text) {
        BUF.append(text).append('\n');
    }
}
