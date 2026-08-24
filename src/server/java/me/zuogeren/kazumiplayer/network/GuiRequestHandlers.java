package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.network.gui.GuiProtocol;
import me.zuogeren.kazumiplayer.network.packet.GuiActionPacket;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.search.BangumiApi;
import me.zuogeren.kazumiplayer.search.RuleSearchSessionCache;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlock;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.sync.PlaybackController;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import net.minecraft.core.Direction;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * 服务端 GUI 通用请求处理：search_bangumi / search_rule / query_chapters /
 * play_episode / join / leave。
 * 与聊天命令共用同一批管理器与会话缓存（GUI 搜到的 resultId 对 /kazumi play 同样有效）。
 */
public class GuiRequestHandlers {

    private static RuleManager ruleManager;
    private static SearchManager searchManager;
    private static final BangumiApi bangumiApi = new BangumiApi();
    private static final RuleSearchSessionCache ruleSessionCache = new RuleSearchSessionCache();

    public static void init(RuleManager rm, SearchManager sm) {
        ruleManager = rm;
        searchManager = sm;
    }

    public static void handle(GuiActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            KazumiLog.network.info("GUI action '{}' at {} from {}", packet.action(),
                packet.screenPos().toShortString(), sp.getName().getString());
            switch (packet.action()) {
                case GuiProtocol.ACTION_SEARCH_BANGUMI -> searchBangumi(sp, packet.payloadJson());
                case GuiProtocol.ACTION_SEARCH_RULE -> searchRule(sp, packet.payloadJson());
                case GuiProtocol.ACTION_CANCEL_SEARCH -> cancelSearch(sp);
                case GuiProtocol.ACTION_QUERY_CHAPTERS -> queryChapters(sp, packet.payloadJson());
                case GuiProtocol.ACTION_PLAY_EPISODE -> playEpisode(sp, packet.screenPos(), packet.payloadJson());
                case GuiProtocol.ACTION_JOIN -> join(sp, packet.screenPos());
                case GuiProtocol.ACTION_LEAVE -> leave(sp, packet.screenPos());
                case GuiProtocol.ACTION_STOP_SCREEN -> stopScreen(sp, packet.screenPos());
                case GuiProtocol.ACTION_SCREEN_PROPS -> screenProps(sp, packet.screenPos(), packet.payloadJson());
                case GuiProtocol.ACTION_QUEUE_ADD -> queueAdd(sp, packet.screenPos(), packet.payloadJson());
                case GuiProtocol.ACTION_QUEUE_PLAY_NOW -> queuePlayNow(sp, packet.screenPos(), packet.payloadJson());
                case GuiProtocol.ACTION_QUEUE_SKIP_CURRENT -> {
                    var err = QueueRequestHandlers.skipCurrent(sp, packet.screenPos());
                    if (err != null) sendError(sp, err); // 成功反馈经队列面板 NBT 同步与通知体现
                }
                case GuiProtocol.ACTION_QUEUE_JUMP -> queueIndexOp(sp, packet.screenPos(), packet.payloadJson(), QueueOp.JUMP);
                case GuiProtocol.ACTION_QUEUE_MOVE -> queueIndexOp(sp, packet.screenPos(), packet.payloadJson(), QueueOp.MOVE);
                case GuiProtocol.ACTION_QUEUE_REMOVE -> queueIndexOp(sp, packet.screenPos(), packet.payloadJson(), QueueOp.REMOVE);
                case GuiProtocol.ACTION_RULE_LIST -> ruleList(sp, packet.payloadJson());
                case GuiProtocol.ACTION_RULE_PULL -> ruleNameOp(sp, packet.payloadJson(), RuleOp.PULL);
                case GuiProtocol.ACTION_RULE_DELETE -> ruleNameOp(sp, packet.payloadJson(), RuleOp.DELETE);
                case GuiProtocol.ACTION_RULE_TEST -> ruleNameOp(sp, packet.payloadJson(), RuleOp.TEST);
                default -> sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.err.unknown_action", packet.action()));
            }
        });
    }

    // ---- 搜索 ----

    private static void searchBangumi(ServerPlayer sp, String payloadJson) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.SearchBangumiPayload.class);
        if (payload == null || payload.keyword().isBlank()) return;
        // 聚合分页拉取完整结果（bgm 端点每页恒 20 条），客户端列表滚动浏览、不翻页
        bangumiApi.searchAll(payload.keyword())
            .thenAccept(subjects -> {
                List<GuiPayloads.BangumiResultItem> items = new ArrayList<>();
                for (var s : subjects) {
                    items.add(new GuiPayloads.BangumiResultItem(
                        s.getDisplayName(),
                        s.getDate() != null ? s.getDate() : "",
                        s.getSummary() != null ? s.getSummary() : ""));
                }
                KazumiLog.network.info("GUI bangumi search '{}': {} results", payload.keyword(), items.size());
                send(sp, GuiProtocol.DATA_BANGUMI_RESULTS, GuiPayloads.toJson(items));
            })
            .exceptionally(e -> {
                KazumiLog.network.warn("GUI bangumi search '{}' failed: {}", payload.keyword(), e.getMessage());
                return sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.err.bgm_failed", String.valueOf(e.getMessage())));
            });
    }

    /** 搜源代次发生器：客户端据此识别新一轮搜索并重置结果列表 */
    private static final java.util.concurrent.atomic.AtomicLong SEARCH_ID_SEQ =
        new java.util.concurrent.atomic.AtomicLong();

    /** 每玩家当前活跃的流式搜源代次：增量回调发送前校验，取消/新搜索即作废旧代次 */
    private static final Map<java.util.UUID, Long> ACTIVE_SEARCHES = new ConcurrentHashMap<>();

    /**
     * 流式规则搜源：逐规则发起搜索，每完成一个源立即推送增量（DATA_RULE_RESULTS_PARTIAL），
     * 慢源/超时源不再阻塞快源结果的展示；单源失败计为一次完成（空增量），计数保证收敛。
     * 发起新搜索自动作废同玩家旧搜索；关闭界面/清空搜索时客户端发 ACTION_CANCEL_SEARCH 取消。
     */
    private static void searchRule(ServerPlayer sp, String payloadJson) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.SearchRulePayload.class);
        if (payload == null || payload.keyword().isBlank()) return;

        Map<String, Rule> rules;
        if (payload.rule().isEmpty()) {
            rules = ruleManager.getRules();
        } else {
            Rule rule = ruleManager.get(payload.rule());
            if (rule == null) {
                sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.rule_not_found", payload.rule()));
                return;
            }
            rules = Map.of(payload.rule(), rule);
        }

        long searchId = SEARCH_ID_SEQ.incrementAndGet();
        ACTIVE_SEARCHES.put(sp.getUUID(), searchId);
        long timeoutMs = Config.CONFIG.searchTimeoutMs.get();
        int total = rules.size();
        AtomicInteger completed = new AtomicInteger();

        for (Rule rule : rules.values()) {
            String ruleName = rule.getName();
            ruleManager.getEngine().search(rule, payload.keyword())
                .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((result, err) -> {
                    if (err != null) {
                        KazumiLog.search.warn("GUI rule search failed for {}: {}", ruleName,
                            err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
                    }
                    List<GuiPayloads.RuleResultItem> items = new ArrayList<>();
                    if (result != null && isActiveSearch(sp.getUUID(), searchId)) {
                        for (var item : result.items()) {
                            String id = searchManager.getCache().store(ruleName, item);
                            items.add(new GuiPayloads.RuleResultItem(ruleName, id, item.name()));
                        }
                    }
                    // 已被取消/被新一轮取代 → 静默丢弃，不向客户端推送迟到结果
                    if (isActiveSearch(sp.getUUID(), searchId)) {
                        send(sp, GuiProtocol.DATA_RULE_RESULTS_PARTIAL, GuiPayloads.toJson(
                            new GuiPayloads.RuleSearchPartialPayload(searchId, ruleName, items,
                                completed.incrementAndGet(), total)));
                    }
                    return null;
                });
        }
    }

    /** 该玩家的流式搜源是否仍然有效（未被取消、未被新一轮取代） */
    private static boolean isActiveSearch(java.util.UUID playerId, long searchId) {
        return Long.valueOf(searchId).equals(ACTIVE_SEARCHES.get(playerId));
    }

    /** 取消本玩家在途的流式搜源（客户端关闭界面/清空搜索时调用） */
    @SuppressWarnings("unused")
    private static void cancelSearch(ServerPlayer sp) {
        ACTIVE_SEARCHES.remove(sp.getUUID());
    }

    private static void queryChapters(ServerPlayer sp, String payloadJson) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.QueryChaptersPayload.class);
        if (payload == null) return;
        var entry = searchManager.getCache().lookup(payload.id());
        if (entry == null) {
            sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.results_expired"));
            return;
        }
        Rule rule = ruleManager.get(payload.rule());
        if (rule == null) {
            sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.rule_not_found", payload.rule()));
            return;
        }
        ruleManager.getEngine().queryChapters(rule, entry.item().src())
            .thenAccept(result -> {
                if (result.roads().isEmpty()) {
                    sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.episodes.not_found_list"));
                    return;
                }
                // 线路选择：road 为 0-based 下标，越界钳制到有效范围（对齐 Kazumi 保持集数序号换线）
                int roadIdx = Math.max(0, Math.min(payload.road(), result.roads().size() - 1));
                Road road = result.roads().get(roadIdx);
                List<String> roadNames = result.roads().stream().map(Road::name).toList();
                int total = road.data().size();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    // payload 只能承载字符串；无集名时回退为纯序号（服务端语言表不含 mod 词条，不可 getString 翻译）
                    names.add(road.identifier().size() > i ? road.identifier().get(i)
                    : String.valueOf(i + 1));
                }
                send(sp, GuiProtocol.DATA_CHAPTERS,
                    GuiPayloads.toJson(new GuiPayloads.ChaptersPayload(roadNames, roadIdx, names, total)));
            })
            .exceptionally(e -> sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.episodes.failed", String.valueOf(e.getMessage()))));
    }

    // ---- 播放 ----

    /** 与 /kazumi play 同路径：查缓存 → 取剧集 → 写 BE NBT + 建同步组 */
    private static void playEpisode(ServerPlayer sp, BlockPos screenPos, String payloadJson) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.PlayEpisodePayload.class);
        if (payload == null) return;
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity)) return;

        var entry = searchManager.getCache().lookup(payload.id());
        if (entry == null) {
            sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.results_expired"));
            return;
        }
        Rule rule = ruleManager.get(payload.rule());
        if (rule == null) {
            sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.rule_not_found", payload.rule()));
            return;
        }
        ruleManager.getEngine().queryChapters(rule, entry.item().src())
            .thenAccept(result -> {
                if (result.roads().isEmpty()) {
                    sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.episodes.not_found_list"));
                    return;
                }
                // 对齐 Kazumi 切线语义：保持集数序号，取目标线路的同序号集；统一钳制避免越界落盘
                int[] posIdx = PlaybackController.clampEpisodePosition(result.roads(), payload.road(), payload.episode());
                int roadIdx = posIdx[0];
                int idx = posIdx[1];
                Road road = result.roads().get(roadIdx);

                // 集名回退为嵌套 translatable：聊天通知直传组件；playOk 的 payload 只能承载字符串，
                // 回退分支换用带集数占位的独立 key（args 全为纯数据字符串）
                boolean hasEpName = road.identifier().size() > idx - 1;
                Component epName = hasEpName ? Component.literal(road.identifier().get(idx - 1))
                    : Component.translatable("kazumiplayer.gui.main.episode_n", idx);

                MinecraftServer server = sp.level().getServer();
                server.execute(() -> {
                    var beNow = sp.level().getBlockEntity(screenPos);
                    if (!(beNow instanceof VideoScreenBlockEntity screen)) return;
                    PlaybackController.playFromSearch(sp, screenPos, screen,
                        result.roads(), roadIdx, idx, entry.item().name());
                    SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, screen.getScreenId(),
                        Component.translatable("kazumiplayer.msg.notify.played_episode",
                            entry.item().name(), epName, road.name()));
                });
                send(sp, GuiProtocol.DATA_PLAY_OK,
                    GuiPayloads.toJson(hasEpName
                        ? GuiPayloads.PlayOkPayload.of("kazumiplayer.msg.ok.played_episode",
                            entry.item().name(), road.identifier().get(idx - 1), road.name())
                        : GuiPayloads.PlayOkPayload.of("kazumiplayer.msg.ok.played_episode_n",
                            entry.item().name(), String.valueOf(idx), road.name())));
            })
            .exceptionally(e -> sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.err.play_failed", String.valueOf(e.getMessage()))));
    }

    // ---- 加入 / 离开（与 /kazumi join、/kazumi play stop 同逻辑）----

    private static void join(ServerPlayer sp, BlockPos screenPos) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return;
        // 业务委托 PlaybackController（与命令层同一实现）；反馈统一走 key 由客户端本地化
        PlaybackController.joinScreen(sp, screenPos, screen);
        sendOkKey(sp, "kazumiplayer.msg.ok.joined");
    }

    private static void leave(ServerPlayer sp, BlockPos screenPos) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return;
        // 通知他人的动词短语走可本地化组件（leaveOwn 组件重载）
        PlaybackController.leaveOwn(sp, screenPos, screen,
            Component.translatable("kazumiplayer.msg.notify.left_sync"));
        PacketDistributor.sendToPlayer(sp, new PlayStopPacket(screenPos));
        sendOkKey(sp, "kazumiplayer.msg.ok.left");
    }

    /** 停止整块屏幕的播放：保存进度 → 清 NBT → 删组 → 向所有观看者发 PlayStopPacket（比 /kazumi screen stop 多通知步骤） */
    private static void stopScreen(ServerPlayer sp, BlockPos screenPos) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return;
        // 唯一权威实现（含 WatchingPlayers 清理与其他观看者的停播包+通知）
        PlaybackController.stopScreen(sp, screenPos, screen,
                Component.translatable("kazumiplayer.msg.notify.stop_screen"));
        KazumiLog.network.info("GUI stop screen {}", screenPos.toShortString());
        sendOkKey(sp, "kazumiplayer.msg.ok.screen_stopped");
    }

    // ---- 屏幕几何属性（朝向/大小/偏移）----

    /**
     * 设置屏幕朝向/大小/XYZ 偏移（GUI「屏幕设置」面板）：
     * clamp 后应用（BlockState.FACING + BE NBT 双写），并回发权威值供面板刷新显示。
     */
    private static void screenProps(ServerPlayer sp, BlockPos screenPos, String payloadJson) {
        var p = GuiPayloads.fromJson(payloadJson, GuiPayloads.ScreenPropsPayload.class);
        if (p == null) return;
        if (!(sp.level().getBlockEntity(screenPos) instanceof VideoScreenBlockEntity screen)) return;

        Direction facing = Direction.byName(p.facing());
        if (facing == null || facing.getAxis() == Direction.Axis.Y) {
            sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.screen.invalid_facing", p.facing()));
            return;
        }
        float width = clamp(p.width(), 0.5f, 128.0f);
        float height = clamp(p.height(), 0.5f, 128.0f);
        float ox = clamp(p.offsetX(), -32.0f, 32.0f);
        float oy = clamp(p.offsetY(), -32.0f, 32.0f);
        float oz = clamp(p.offsetZ(), -32.0f, 32.0f);

        // 朝向双写：方块本体模型读 BlockState，渲染器画面读 BE NBT——必须同步更新
        var state = sp.level().getBlockState(screenPos);
        if (state.hasProperty(me.zuogeren.kazumiplayer.screen.VideoScreenBlock.FACING)) {
            sp.level().setBlock(screenPos, state.setValue(me.zuogeren.kazumiplayer.screen.VideoScreenBlock.FACING, facing), 3);
        }
        screen.setFacing(facing);
        screen.setScreenSize(width, height);
        screen.setScreenOffset(ox, oy, oz);

        send(sp, GuiProtocol.DATA_SCREEN_PROPS, GuiPayloads.toJson(
            new GuiPayloads.ScreenPropsPayload(ox, oy, oz, facing.getName(), width, height)));
        KazumiLog.network.info("Screen props updated at {} by {}", screenPos.toShortString(),
            sp.getName().getString());
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- 直链队列（实现见 QueueRequestHandlers）----

    private enum QueueOp { JUMP, MOVE, REMOVE }

    private static void queueAdd(ServerPlayer sp, BlockPos screenPos, String payloadJson) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.QueueAddPayload.class);
        if (payload == null || payload.urls() == null || payload.urls().isEmpty()) return;
        var err = QueueRequestHandlers.submit(sp, screenPos, payload.urls());
        if (err != null) sendError(sp, err);
    }

    /** 立即切播直链（频道目录「切」按钮）：payload 复用 {urls}，取第一条 */
    private static void queuePlayNow(ServerPlayer sp, BlockPos screenPos, String payloadJson) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.QueueAddPayload.class);
        if (payload == null || payload.urls() == null || payload.urls().isEmpty()) return;
        var err = QueueRequestHandlers.playNow(sp, screenPos, payload.urls().get(0));
        if (err != null) sendError(sp, err);
    }

    /** jump/move/remove 共用：payload 均为 {index}，成功反馈经队列面板 NBT 同步与聊天通知体现 */
    private static void queueIndexOp(ServerPlayer sp, BlockPos screenPos, String payloadJson, QueueOp op) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.QueueIndexPayload.class);
        if (payload == null) return;
        GuiPayloads.ErrorPayload err = switch (op) {
            case JUMP -> QueueRequestHandlers.jump(sp, screenPos, payload.index());
            case MOVE -> QueueRequestHandlers.moveAfterCurrent(sp, screenPos, payload.index());
            case REMOVE -> QueueRequestHandlers.remove(sp, screenPos, payload.index());
        };
        if (err != null) sendError(sp, err);
    }

    // ---- 规则管理器（RuleManagerScreen）----

    private enum RuleOp { PULL, DELETE, TEST }

    /**
     * 远程目录 + 本地安装状态合并为列表回推。
     * refresh=false（打开界面）读服务端目录缓存；refresh=true（「刷新列表」按钮）强制拉取云端。
     */
    private static void ruleList(ServerPlayer sp, String payloadJson) {
        boolean force = false;
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.RuleListPayload.class);
        if (payload != null) force = payload.refresh();
        ruleManager.getDownloader().fetchIndex(force)
            .thenAccept(index -> {
                List<GuiPayloads.RuleListEntryPayload> entries = new ArrayList<>();
                // 已安装排最前（本地版本/弃用状态以本地为准，作者取远程目录补充）
                for (String name : ruleManager.listAll()) {
                    Rule local = ruleManager.get(name);
                    if (local == null) continue;
                    entries.add(new GuiPayloads.RuleListEntryPayload(name, true,
                        local.getVersion(), findRemoteMeta(index, name, true),
                        local.isDeprecated(), findRemoteMeta(index, name, false)));
                }
                // 远程未安装的
                for (var ri : index) {
                    if (!ruleManager.getRules().containsKey(ri.getName())) {
                        entries.add(new GuiPayloads.RuleListEntryPayload(ri.getName(), false,
                            "", ri.getVersion(), false, ri.getAuthor()));
                    }
                }
                send(sp, GuiProtocol.DATA_RULE_LIST, GuiPayloads.toJson(entries));
            })
            .exceptionally(e -> sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.rule.catalog_failed", friendly(e))));
    }

    private static void ruleNameOp(ServerPlayer sp, String payloadJson, RuleOp op) {
        var payload = GuiPayloads.fromJson(payloadJson, GuiPayloads.RuleNamePayload.class);
        if (payload == null || payload.name().isBlank()) return;
        String name = payload.name();
        switch (op) {
            case PULL -> {
                ruleManager.getDownloader().fetchRule(name)
                    .thenAccept(rule -> {
                        ruleManager.install(rule);
                        broadcastRuleSync();
                        sendOkKey(sp, "kazumiplayer.cmd.rule.installed", name);
                        refreshListFor(sp);
                    })
                    .exceptionally(e -> {
                        sendErrorKey(sp, "kazumiplayer.cmd.rule.download_failed",
                            ruleManager.getDownloader().friendlyError(name, e));
                        return null;
                    });
            }
            case DELETE -> {
                if (ruleManager.delete(name)) {
                    broadcastRuleSync();
                    sendOkKey(sp, "kazumiplayer.cmd.rule.deleted", name);
                    refreshListFor(sp);
                } else {
                    sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.rule_not_found", name));
                }
            }
            case TEST -> {
                Rule rule = ruleManager.get(name);
                if (rule == null) {
                    sendError(sp, GuiPayloads.ErrorPayload.of("kazumiplayer.cmd.rule_not_found", name));
                    return;
                }
                long start = System.currentTimeMillis();
                ruleManager.getEngine().search(rule, "test")
                    .thenAccept(r -> send(sp, GuiProtocol.DATA_RULE_TEST_RESULT,
                        GuiPayloads.toJson(new GuiPayloads.RuleTestResultPayload(
                            name, System.currentTimeMillis() - start, true))))
                    .exceptionally(e -> {
                        send(sp, GuiProtocol.DATA_RULE_TEST_RESULT,
                            GuiPayloads.toJson(new GuiPayloads.RuleTestResultPayload(name, -1, false)));
                        return null;
                    });
            }
        }
    }

    /** 安装/删除后把合并列表重新推送给请求者，GUI 无需手动刷新（读缓存目录，本地状态实时合并） */
    private static void refreshListFor(ServerPlayer sp) {
        ruleList(sp, "{}"); // refresh 缺省 = false，读缓存
    }

    private static String findRemoteMeta(List<me.zuogeren.kazumiplayer.rule.RuleIndex> index,
            String name, boolean version) {
        for (var ri : index) {
            if (ri.getName().equals(name)) return version ? ri.getVersion() : ri.getAuthor();
        }
        return "";
    }

    private static String friendly(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null || t.getMessage().isEmpty()
            ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 规则变更后向所有在线玩家广播（客户端 /krule 与缓存依赖此同步）；空列表也广播以清空客户端缓存 */
    private static void broadcastRuleSync() {
        var rules = ruleManager.listAll();
        String json = JsonUtil.GSON.toJson(
            rules.stream().map(ruleManager::get).filter(r -> r != null).toList());
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player,
                new me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket(json));
        }
    }

    // ---- 辅助 ----

    private static void send(ServerPlayer sp, String dataType, String payloadJson) {
        PacketDistributor.sendToPlayer(sp, new GuiDataPacket(dataType, payloadJson));
    }

    private static void sendOk(ServerPlayer sp, String message) {
        send(sp, GuiProtocol.DATA_PLAY_OK, GuiPayloads.toJson(GuiPayloads.PlayOkPayload.literal(message)));
    }

    private static void sendOkKey(ServerPlayer sp, String key, String... args) {
        send(sp, GuiProtocol.DATA_PLAY_OK, GuiPayloads.toJson(GuiPayloads.PlayOkPayload.of(key, args)));
    }

    private static Void sendError(ServerPlayer sp, String message) {
        send(sp, GuiProtocol.DATA_ERROR, GuiPayloads.toJson(GuiPayloads.ErrorPayload.literal(message)));
        KazumiLog.network.debug("GUI request error for {}: {}", sp.getName().getString(), message);
        return null;
    }

    private static Void sendError(ServerPlayer sp, GuiPayloads.ErrorPayload payload) {
        send(sp, GuiProtocol.DATA_ERROR, GuiPayloads.toJson(payload));
        KazumiLog.network.debug("GUI request error (localized) for {}", sp.getName().getString());
        return null;
    }

    /** 回推可本地化的错误（key 由客户端语言文件渲染） */
    private static Void sendErrorKey(ServerPlayer sp, String key, String... args) {
        send(sp, GuiProtocol.DATA_ERROR, GuiPayloads.toJson(GuiPayloads.ErrorPayload.of(key, args)));
        KazumiLog.network.debug("GUI request error for {}: {} ({})", sp.getName().getString(), key, String.join(",", args));
        return null;
    }
}
