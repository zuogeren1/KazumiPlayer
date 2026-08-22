package me.zuogeren.kazumiplayer.client.gui;

import com.google.gson.reflect.TypeToken;
import me.zuogeren.kazumiplayer.client.ScreenPlayerManager;
import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.network.gui.GuiProtocol;
import me.zuogeren.kazumiplayer.network.packet.GuiActionPacket;
import me.zuogeren.kazumiplayer.network.packet.PlaybackControlPacket;
import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderer;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.List;

/**
 * 播放器式主界面（右键屏幕打开，绑定该屏幕）：
 * 顶栏直链输入 | 左列搜索流（bgm 结果→规则源结果）| 右上视频预览 + 右下选集/简介 |
 * 底部进度条与播放控制。打开时不暂停世界（isPauseScreen=false）。
 */
public class KazumiPlayerScreen extends Screen implements GuiClientState.Listener {

    private static final int ROW_HEIGHT = 18;
    /** seek 提交后滑块反向同步的豁免窗口（等播放器真正完成跳转） */
    private static final long SEEK_SYNC_GRACE_MS = 5000;
    /** 操作提示条自动隐藏时长 */
    private static final long STATUS_TOAST_MS = 6000;

    private final BlockPos screenPos;

    // 左列搜索流状态（static：GUI 关闭重开后保留上次搜索，clearSearch() 清空）
    private enum ListView { BANGUMI, RULE }
    private static ListView listView = ListView.BANGUMI;
    private static List<GuiPayloads.BangumiResultItem> bangumiItems = List.of();
    private static List<GuiPayloads.RuleResultItem> ruleItems = List.of();
    private static String lastKeyword = "";

    // 右列状态（同上，随 GUI 会话持久化）
    private enum DetailView { EPISODES, SUMMARY }
    private static DetailView detailView = DetailView.EPISODES;
    private static List<String> episodeNames = List.of();
    private static List<String> roadNames = List.of();   // 播放线路名列表
    private static int selectedRoad;                     // 当前线路 (0-based)
    private static String selectedRule = "";
    private static String selectedResultId = "";
    private static String subjectSummary = "";
    private static String subjectName = "";

    // 底部控制状态
    private String statusTitle = "";
    private long statusTitleAt;        // 提示设置时刻（操作提示条超时自动隐藏）
    private long pendingSeekMs = -1;
    private long lastSeekSentAt;

    private void setStatus(String text) {
        this.statusTitle = text == null ? "" : text;
        this.statusTitleAt = System.currentTimeMillis();
    }

    // 组件
    private EditBox urlEdit;
    private EditBox searchEdit;
    private SimpleList searchList;
    private SimpleList episodeList;
    private MultiLineTextWidget summaryWidget;
    private Button episodesTab;
    private Button summaryTab;
    private Button roadButton;
    private boolean roadDropdownOpen;
    private SimpleList queueList;      // 直链播放队列（占位，未来支持多直链排队）
    private SimpleList watchList;      // 当前正在观看的玩家
    private int watchRefreshCounter;
    private Button pauseButton;
    private SeekSlider seekSlider;

    public KazumiPlayerScreen(BlockPos screenPos) {
        super(Component.literal("KazumiPlayer"));
        this.screenPos = screenPos;
    }

    /** 打开 GUI 不暂停单人游戏世界 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void init() {
        int w = this.width, h = this.height;
        int leftW = Math.max(150, w * 2 / 5);
        int sideW = Math.min(140, Math.max(104, w / 5));   // 最右列（队列/观看玩家），压缩显示区宽度
        int rightX = 6 + leftW + 8;
        int rightW = w - rightX - sideW - 14;              // 中列（预览+选集/简介/线路）
        int sideX = w - sideW - 6;
        int listBottom = h - 78;

        // ---- 顶栏：直链 ----
        this.urlEdit = new EditBox(this.font, 6, 6, w - 118, 16, Component.literal("直链"));
        this.urlEdit.setMaxLength(2048);
        this.urlEdit.setHint(Component.literal("粘贴视频直链地址..."));
        this.addRenderableWidget(this.urlEdit);
        this.addRenderableWidget(Button.builder(Component.literal("播放"), b -> this.playDirectUrl())
            .bounds(w - 106, 6, 50, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose())
            .bounds(w - 52, 6, 46, 16).build());

        // ---- 左列：搜索行 + 结果列表 ----
        // 四个操作：搜索(bgm 番剧) / 搜源(跳过 bgm 直接搜全部规则源，bgm 不可达时用) / 清空 / 返回
        int btnW = 32;
        int rowBtnsX = 6 + leftW - (btnW * 4 + 12);
        this.searchEdit = new EditBox(this.font, 6, 30, leftW - (btnW * 4 + 12) - 4, 16, Component.literal("搜索"));
        this.searchEdit.setMaxLength(128);
        this.searchEdit.setHint(Component.literal("搜索番剧或直接搜源..."));
        this.addRenderableWidget(this.searchEdit);
        this.addRenderableWidget(Button.builder(Component.literal("搜索"), b -> this.doBangumiSearch())
            .bounds(rowBtnsX, 30, btnW, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("搜源"), b -> this.doDirectRuleSearch())
            .bounds(rowBtnsX + btnW + 4, 30, btnW, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("清空"), b -> this.clearSearch())
            .bounds(rowBtnsX + (btnW + 4) * 2, 30, btnW, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.showBangumiView())
            .bounds(rowBtnsX + (btnW + 4) * 3, 30, btnW, 16).build());

        this.searchList = new SimpleList(this.minecraft, leftW, listBottom - 50, 50, ROW_HEIGHT);
        this.searchList.setX(6);
        this.addRenderableWidget(this.searchList);

        // ---- 右上：视频预览区（extractRenderState 自绘）----
        int previewH = Math.max(56, (listBottom - 30 - 22) * 9 / 20);
        int splitY = 30 + previewH;

        // ---- 右下：选集 / 简介 / 线路下拉 ----
        this.episodesTab = Button.builder(Component.literal("选集"), b -> this.switchDetail(DetailView.EPISODES))
            .bounds(rightX, splitY, 52, 16).build();
        this.summaryTab = Button.builder(Component.literal("简介"), b -> this.switchDetail(DetailView.SUMMARY))
            .bounds(rightX + 56, splitY, 52, 16).build();
        this.roadButton = Button.builder(Component.literal("线路"), b -> this.roadDropdownOpen = !this.roadDropdownOpen)
            .bounds(rightX + 112, splitY, Math.min(72, rightW - 112), 16).build();
        this.addRenderableWidget(this.episodesTab);
        this.addRenderableWidget(this.summaryTab);
        this.addRenderableWidget(this.roadButton);

        int detailTop = splitY + 20;
        this.episodeList = new SimpleList(this.minecraft, rightW, listBottom - detailTop, detailTop, ROW_HEIGHT);
        this.episodeList.setX(rightX);
        this.addRenderableWidget(this.episodeList);

        this.summaryWidget = new MultiLineTextWidget(
            Component.literal(this.subjectSummary.isEmpty() ? "（在左侧选择番剧后显示简介）" : this.subjectSummary),
            this.font);
        this.summaryWidget.setMaxWidth(rightW - 8);
        this.summaryWidget.setPosition(rightX + 2, detailTop + 2);
        this.addRenderableWidget(this.summaryWidget);

        // ---- 最右列：播放队列（占位）+ 正在观看的玩家 ----
        int panelH = Math.max(40, (listBottom - 44) / 2 - 8);
        this.queueList = new SimpleList(this.minecraft, sideW, panelH, 44, ROW_HEIGHT);
        this.queueList.setX(sideX);
        this.addRenderableWidget(this.queueList);
        this.watchList = new SimpleList(this.minecraft, sideW, panelH, 44 + panelH + 16, ROW_HEIGHT);
        this.watchList.setX(sideX);
        this.addRenderableWidget(this.watchList);

        // ---- 底部：状态行 + 进度条 + 控制按钮 ----
        this.seekSlider = new SeekSlider(8, h - 46, w - 16, 14);
        this.addRenderableWidget(this.seekSlider);

        int by = h - 28;
        int bw = Math.max(44, (w - 12 - 7 * 4) / 8);
        this.addRenderableWidget(Button.builder(Component.literal("上一集"), b -> this.sendControl("prev", 0))
            .bounds(6, by, bw, 18).build());
        this.pauseButton = Button.builder(Component.literal("暂停"), b -> this.togglePause())
            .bounds(6 + (bw + 4), by, bw, 18).build();
        this.addRenderableWidget(this.pauseButton);
        this.addRenderableWidget(Button.builder(Component.literal("下一集"), b -> this.sendControl("next", 0))
            .bounds(6 + (bw + 4) * 2, by, bw, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("-10s"), b -> this.sendControl("seek_back", 10))
            .bounds(6 + (bw + 4) * 3, by, bw, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+10s"), b -> this.sendControl("seek_forward", 10))
            .bounds(6 + (bw + 4) * 4, by, bw, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("加入同步"), b -> this.sendAction(GuiProtocol.ACTION_JOIN, "{}"))
            .bounds(6 + (bw + 4) * 5, by, bw, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("离开"), b -> this.sendAction(GuiProtocol.ACTION_LEAVE, "{}"))
            .bounds(6 + (bw + 4) * 6, by, bw, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("停止屏幕"), b -> this.stopScreen())
            .bounds(6 + (bw + 4) * 7, by, bw, 18).build());

        this.switchDetail(this.detailView);
        this.rebuildSearchList();
        this.rebuildEpisodeList();
        this.rebuildQueueList();
        this.rebuildWatchList();
        GuiClientState.setListener(this);
    }

    @Override
    public void removed() {
        GuiClientState.setListener(null);
        super.removed();
    }

    @Override
    public void resize(int width, int height) {
        String url = this.urlEdit != null ? this.urlEdit.getValue() : "";
        String search = this.searchEdit != null ? this.searchEdit.getValue() : "";
        this.init(width, height);
        this.urlEdit.setValue(url);
        this.searchEdit.setValue(search);
    }

    @Override
    public void tick() {
        super.tick();
        this.seekSlider.syncWithPlayer();
        var player = this.getPlayer();
        this.pauseButton.setMessage(Component.literal(player != null && player.isPlaying() ? "暂停" : "播放"));
        // 观看者名单低频刷新（TabList 名字解析 + BE 同步均有延迟）
        if (++this.watchRefreshCounter % 20 == 0) this.rebuildWatchList();
    }

    // ---- 渲染 ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int w = this.width, h = this.height;
        int leftW = Math.max(150, w * 2 / 5);
        int sideW = Math.min(140, Math.max(104, w / 5));
        int rightX = 6 + leftW + 8;
        int rightW = w - rightX - sideW - 14;
        int sideX = w - sideW - 6;
        int listBottom = h - 78;
        int previewH = Math.max(56, (listBottom - 30 - 22) * 9 / 20);

        this.drawPreview(graphics, rightX, 30, rightW, previewH);

        // 最右列面板标题
        graphics.text(this.font, Component.literal("队列").withStyle(ChatFormatting.GRAY), sideX, 30, -1);
        graphics.text(this.font, Component.literal("观看中").withStyle(ChatFormatting.GRAY),
            sideX, 44 + Math.max(40, (listBottom - 44) / 2 - 8) + 4, -1);

        this.drawPreview(graphics, rightX, 30, rightW, previewH);

        // 线路下拉展开层（渲染在预览区之上）
        if (this.roadDropdownOpen && !this.roadNames.isEmpty()) {
            int dx = this.roadButton.getX();
            int dw = Math.max(this.roadButton.getWidth(), 110);
            int dy = this.roadButton.getY() + 16;
            for (int i = 0; i < this.roadNames.size(); i++) {
                int y0 = dy + i * ROW_HEIGHT;
                boolean hovered = mouseX >= dx && mouseX < dx + dw && mouseY >= y0 && mouseY < y0 + ROW_HEIGHT;
                graphics.fill(dx, y0, dx + dw, y0 + ROW_HEIGHT,
                    hovered ? 0xFF3C3C52 : (i == selectedRoad ? 0xF0252545 : 0xE0000000));
                String label = (i == selectedRoad ? "> " : "") + this.roadNames.get(i);
                graphics.text(this.font, Component.literal(label).withStyle(ChatFormatting.GRAY), dx + 4, y0 + 4, -1);
            }
        }

        // ---- 左下状态区（两行）：操作提示条（醒目、6 秒自动隐藏）+ 正在播放信息 ----
        // 提示条背景 [h-75, h-61] 位于列表底(h-78)之下不再重叠；正在播放行贴进度条上方
        int yToast = h - 75;
        if (!this.statusTitle.isEmpty() && System.currentTimeMillis() - this.statusTitleAt < STATUS_TOAST_MS) {
            String t = "» " + this.statusTitle;
            int tw = this.font.width(t);
            graphics.fill(5, yToast, 12 + tw + 6, yToast + 14, 0xE0101008);
            graphics.fill(5, yToast, 9, yToast + 14, 0xFFFFC94A);
            graphics.text(this.font,
                Component.literal(t).withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.YELLOW),
                14, yToast + 3, -1);
        }
        String nowPlaying = this.buildNowPlayingLine();
        if (!nowPlaying.isEmpty()) {
            graphics.text(this.font,
                Component.literal(nowPlaying).withStyle(ChatFormatting.YELLOW), 8, h - 59, -1);
        }
        var player = this.getPlayer();
        String time = player != null
            ? KazumiMessages.formatMs(Math.max(0, player.getTimeMs())) + " / " + KazumiMessages.formatMs(player.getDurationMs())
            : "--:-- / --:--";
        graphics.text(this.font, time, w - 8 - this.font.width(time), h - 60, -1);
    }

    /**
     * "正在播放"行：番剧名 · 集名 · 线路名（数据来自绑定屏幕 BE 的 NBT）。
     * 直链播放时番剧名为空，整行不显示。
     */
    private String buildNowPlayingLine() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "";
        var beEntity = mc.level.getBlockEntity(this.screenPos);
        if (!(beEntity instanceof me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity vsb)
                || vsb.getPlayingTitle().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("正在播放: ").append(vsb.getPlayingTitle());
        List<me.zuogeren.kazumiplayer.rule.dto.Road> roads =
            me.zuogeren.kazumiplayer.util.JsonUtil.parseRoads(vsb.getEpisodeData());
        if (roads != null && !roads.isEmpty()) {
            int ri = Math.max(0, Math.min(vsb.getRoadIndex(), roads.size() - 1));
            var road = roads.get(ri);
            int idx = Math.max(1, vsb.getEpisodeIndex());
            String epName = road.identifier().size() >= idx
                ? road.identifier().get(idx - 1) : ("第" + idx + "集");
            sb.append(" · ").append(epName).append(" · ").append(road.name());
        }
        return sb.toString();
    }

    /** 右上视频预览：把绑定屏幕的动态纹理按比例 blit 进区域，无帧时画占位 */
    private void drawPreview(GuiGraphicsExtractor graphics, int x, int y, int areaW, int areaH) {
        graphics.fill(x, y, x + areaW, y + areaH, 0xFF000000);
        var tex = VideoScreenRenderer.getScreenTexture(this.screenPos);
        var player = this.getPlayer();
        if (tex == null || !tex.hasValidFrame() || player == null
                || player.getWidth() <= 0 || player.getHeight() <= 0) {
            graphics.centeredText(this.font, Component.literal("无视频信号"), x + areaW / 2, y + areaH / 2 - 4, 0xFF888888);
            return;
        }
        // 等比缩放适配区域
        double scale = Math.min((double) areaW / player.getWidth(), (double) areaH / player.getHeight());
        int pw = Math.max(1, (int) (player.getWidth() * scale));
        int ph = Math.max(1, (int) (player.getHeight() * scale));
        int px = x + (areaW - pw) / 2;
        int py = y + (areaH - ph) / 2;
        graphics.blit(tex.getTextureId(), px, py, px + pw, py + ph, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    // ---- 数据到达（网络线程已 enqueueWork 到主线程）----

    @Override
    public void onGuiData(String dataType, String json) {
        switch (dataType) {
            case GuiProtocol.DATA_BANGUMI_RESULTS -> {
                this.bangumiItems = GuiPayloads.fromJson(json,
                    new TypeToken<List<GuiPayloads.BangumiResultItem>>() {}.getType());
                this.showBangumiView();
                if (this.bangumiItems.isEmpty()) setStatus("未找到结果");
            }
            case GuiProtocol.DATA_RULE_RESULTS -> {
                this.ruleItems = GuiPayloads.fromJson(json,
                    new TypeToken<List<GuiPayloads.RuleResultItem>>() {}.getType());
                this.listView = ListView.RULE;
                this.rebuildSearchList();
                if (this.ruleItems.isEmpty()) setStatus("各源均未搜到结果");
            }
            case GuiProtocol.DATA_CHAPTERS -> {
                var p = GuiPayloads.fromJson(json, GuiPayloads.ChaptersPayload.class);
                if (p != null) {
                    this.roadNames = p.roads() != null ? p.roads() : List.of();
                    this.selectedRoad = this.roadNames.isEmpty() ? 0
                        : Math.max(0, Math.min(p.road(), this.roadNames.size() - 1));
                    this.episodeNames = p.names();
                    this.updateRoadButtonLabel();
                    this.rebuildEpisodeList();
                    this.switchDetail(DetailView.EPISODES);
                }
            }
            case GuiProtocol.DATA_PLAY_OK -> {
                // 播放成功：清空操作提示，由下方"正在播放"信息行展示结果（避免两处内容重复）
                setStatus("");
            }
            case GuiProtocol.DATA_ERROR -> {
                var p = GuiPayloads.fromJson(json, GuiPayloads.ErrorPayload.class);
                if (p != null) {
                    setStatus(p.message());
                    KazumiLog.network.warn("GUI action failed: {}", p.message());
                    if (this.minecraft.player != null) {
                        KazumiMessages.sendError(this.minecraft.player, p.message());
                    }
                }
            }
            default -> {}
        }
    }

    // ---- 左列视图 ----

    private void showBangumiView() {
        this.listView = ListView.BANGUMI;
        this.rebuildSearchList();
    }

    private void rebuildSearchList() {
        this.searchList.clearEntries();
        if (this.listView == ListView.BANGUMI) {
            for (int i = 0; i < this.bangumiItems.size(); i++) {
                var item = this.bangumiItems.get(i);
                String date = item.date().isEmpty() ? "" : " (" + item.date() + ")";
                final var selected = item;
                this.searchList.addRow(Component.literal((i + 1) + ". " + item.name() + date), -1,
                    () -> this.selectSubject(selected));
            }
        } else {
            for (int i = 0; i < this.ruleItems.size(); i++) {
                var item = this.ruleItems.get(i);
                final var selected = item;
                this.searchList.addRow(
                    Component.literal("[" + item.rule() + "] " + item.name()).withStyle(ChatFormatting.GOLD), -1,
                    () -> this.selectRuleResult(selected));
            }
        }
    }

    /** 点击番剧 → 在全部规则中搜索该名（对齐聊天 [查源]），并记录简介 */
    private void selectSubject(GuiPayloads.BangumiResultItem item) {
        this.subjectName = item.name();
        this.subjectSummary = item.summary();
        this.summaryWidget.setMessage(Component.literal(
            this.subjectSummary.isEmpty() ? "（无简介）" : this.subjectSummary));
        // 切换番剧：清空上一部作品的选中状态与选集，避免残留误导
        this.selectedRule = "";
        this.selectedResultId = "";
        this.episodeNames = List.of();
        this.rebuildEpisodeList();
        setStatus("正在所有规则中搜索: " + item.name());
        this.lastKeyword = item.name();
        this.sendAction(GuiProtocol.ACTION_SEARCH_RULE,
            new GuiPayloads.SearchRulePayload("", item.name()));
    }

    /** 点击规则结果 → 拉取集数列表（重置回第一线路） */
    private void selectRuleResult(GuiPayloads.RuleResultItem item) {
        this.selectedRule = item.rule();
        this.selectedResultId = item.id();
        this.selectedRoad = 0;
        this.roadNames = List.of();
        setStatus("获取集数: [" + item.rule() + "] " + item.name());
        this.sendAction(GuiProtocol.ACTION_QUERY_CHAPTERS,
            new GuiPayloads.QueryChaptersPayload(item.rule(), item.id(), this.selectedRoad));
    }

    // ---- 右下列表 ----

    private void switchDetail(DetailView view) {
        this.detailView = view;
        boolean episodes = view == DetailView.EPISODES;
        this.episodeList.visible = episodes;
        this.summaryWidget.visible = !episodes;
        this.episodesTab.active = !episodes;
        this.summaryTab.active = episodes;
    }

    private void rebuildEpisodeList() {
        this.episodeList.clearEntries();
        if (this.episodeNames.isEmpty()) return;
        // 横向网格：按列表宽度自动定列数（每格约 72px）
        int cols = Math.max(3, this.episodeList.getWidth() / 72);
        for (int start = 0; start < this.episodeNames.size(); start += cols) {
            int end = Math.min(start + cols, this.episodeNames.size());
            List<SimpleList.Cell> cells = new java.util.ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                final int ep = i + 1;
                cells.add(new SimpleList.Cell(this.episodeNames.get(i), () -> this.playEpisode(ep)));
            }
            this.episodeList.addGridRow(cells);
        }
    }

    /** 清空搜索状态与界面（搜索结果/规则结果/选集/简介/选中项） */
    private void clearSearch() {
        resetSearchState();
        this.searchEdit.setValue("");
        this.rebuildSearchList();
        this.rebuildEpisodeList();
        this.summaryWidget.setMessage(Component.literal("（在左侧选择番剧后显示简介）"));
        setStatus("已清空搜索");
    }

    private static void resetSearchState() {
        listView = ListView.BANGUMI;
        bangumiItems = List.of();
        ruleItems = List.of();
        lastKeyword = "";
        detailView = DetailView.EPISODES;
        episodeNames = List.of();
        roadNames = List.of();
        selectedRoad = 0;
        selectedRule = "";
        selectedResultId = "";
        subjectSummary = "";
        subjectName = "";
    }

    /** 停止整块屏幕的播放（所有观看者一起停） */
    private void stopScreen() {
        this.sendAction(GuiProtocol.ACTION_STOP_SCREEN, "{}");
        setStatus("已请求停止屏幕播放");
    }

    // ---- 最右列面板 ----

    /** 播放队列（占位）：未来支持输入多个直链排队依次播放 */
    private void rebuildQueueList() {
        this.queueList.clearEntries();
        this.queueList.addRow(
            Component.literal("（直链队列 · 开发中）").withStyle(ChatFormatting.DARK_GRAY), -1, null);
    }

    /** 观看者列表：BE 同步的 WatchingPlayers UUID 经客户端 TabList 解析为玩家名 */
    private void rebuildWatchList() {
        this.watchList.clearEntries();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var beEntity = mc.level.getBlockEntity(this.screenPos);
        if (!(beEntity instanceof me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity screen)
                || screen.getWatchingPlayers().isEmpty()) {
            this.watchList.addRow(
                Component.literal("（暂无观看者）").withStyle(ChatFormatting.DARK_GRAY), -1, null);
            return;
        }
        for (String u : screen.getWatchingPlayers().split(",")) {
            String name;
            try {
                var info = mc.getConnection() != null
                    ? mc.getConnection().getPlayerInfo(java.util.UUID.fromString(u.trim())) : null;
                name = info != null && !info.getProfile().name().isEmpty()
                    ? info.getProfile().name() : u.substring(0, Math.min(8, u.length())) + "…";
            } catch (Exception e) {
                continue;
            }
            boolean self = mc.player != null && name.equals(mc.player.getGameProfile().name());
            this.watchList.addRow(Component.literal(self ? "▶ " + name : name), -1, null);
        }
    }

    // ---- 操作 ----

    private void playDirectUrl() {
        String url = this.urlEdit.getValue().trim();
        if (url.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundCustomPayloadPacket(
                new me.zuogeren.kazumiplayer.network.packet.PlayUrlPacket(this.screenPos, url)));
        }
        setStatus("已提交直链播放");
    }

    private void doBangumiSearch() {
        String keyword = this.searchEdit.getValue().trim();
        if (keyword.isEmpty()) return;
        this.lastKeyword = keyword;
        setStatus("正在搜索: " + keyword + " ...");
        this.sendAction(GuiProtocol.ACTION_SEARCH_BANGUMI, new GuiPayloads.SearchBangumiPayload(keyword));
    }

    /** 跳过 bgm 直接在全部已装规则中搜索关键词（bgm 不可达时的替代路径） */
    private void doDirectRuleSearch() {
        String keyword = this.searchEdit.getValue().trim();
        if (keyword.isEmpty()) return;
        this.lastKeyword = keyword;
        setStatus("正在所有规则中搜索: " + keyword + " ...");
        this.sendAction(GuiProtocol.ACTION_SEARCH_RULE,
            new GuiPayloads.SearchRulePayload("", keyword));
    }

    private void playEpisode(int ep) {
        if (this.selectedRule.isEmpty() || this.selectedResultId.isEmpty()) return;
        this.sendAction(GuiProtocol.ACTION_PLAY_EPISODE,
            new GuiPayloads.PlayEpisodePayload(this.selectedRule, this.selectedResultId, ep, this.selectedRoad));
    }

    // ---- 线路下拉 ----

    /** 切换线路（对齐 Kazumi：保持集数序号，重新拉取目标线路的集数列表） */
    private void selectRoad(int idx) {
        this.roadDropdownOpen = false;
        if (idx < 0 || idx >= this.roadNames.size() || idx == this.selectedRoad) return;
        if (this.selectedRule.isEmpty() || this.selectedResultId.isEmpty()) return;
        this.selectedRoad = idx;
        setStatus("切换到线路: " + this.roadNames.get(idx));
        this.sendAction(GuiProtocol.ACTION_QUERY_CHAPTERS,
            new GuiPayloads.QueryChaptersPayload(this.selectedRule, this.selectedResultId, this.selectedRoad));
    }

    private void updateRoadButtonLabel() {
        if (this.roadButton == null) return;
        String label = this.roadNames.size() > 1
            ? "线路 " + (this.selectedRoad + 1) + "/" + this.roadNames.size()
            : (this.roadNames.isEmpty() ? "线路" : "线路 1/1");
        this.roadButton.setMessage(Component.literal(label));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 下拉展开时优先处理：点中选项则切线；点击下拉区域外则收起
        if (this.roadDropdownOpen && !this.roadNames.isEmpty()) {
            int dx = this.roadButton.getX();
            int dw = Math.max(this.roadButton.getWidth(), 110);
            int dy = this.roadButton.getY() + 16;
            int mx = (int) event.x(), my = (int) event.y();
            if (mx >= dx && mx < dx + dw && my >= dy && my < dy + this.roadNames.size() * ROW_HEIGHT) {
                selectRoad((my - dy) / ROW_HEIGHT);
                return true;
            }
            this.roadDropdownOpen = false;
        }
        return super.mouseClicked(event, doubled);
    }

    private void sendControl(String action, long value) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundCustomPayloadPacket(
                new PlaybackControlPacket(this.screenPos, action, value)));
        }
    }

    private void togglePause() {
        var player = this.getPlayer();
        this.sendControl(player != null && player.isPlaying() ? "pause" : "resume", 0);
    }

    private void sendAction(String action, Object payloadJson) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            KazumiLog.network.info("GUI send action '{}' at {}", action, this.screenPos.toShortString());
            mc.getConnection().send(new ServerboundCustomPayloadPacket(
                new GuiActionPacket(this.screenPos, action,
                    payloadJson instanceof String s ? s : GuiPayloads.toJson(payloadJson))));
        } else {
            KazumiLog.network.warn("GUI send action '{}' failed: no connection", action);
        }
    }

    private WaterMediaPlayer getPlayer() {
        return ScreenPlayerManager.getPlayer(this.screenPos);
    }

    /**
     * 进度条：拖动中只更新本地目标位置，松手才发 seek_goto；
     * 非拖动时每 tick 反向同步当前播放位置。
     */
    private class SeekSlider extends AbstractSliderButton {
        private boolean dragging;

        private SeekSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal(""), 0.0);
            this.syncWithPlayer();
        }

        private long durationMs() {
            var p = getPlayer();
            return p != null ? Math.max(0, p.getDurationMs()) : 0;
        }

        private long currentMs() {
            var p = getPlayer();
            return p != null ? Math.max(0, p.getTimeMs()) : 0;
        }

        void syncWithPlayer() {
            long dur = durationMs();
            // 无播放器/未起播（含切集重建瞬间）：滑块清零，避免残留上一集位置
            if (dur <= 0) {
                if (this.value != 0.0) {
                    this.value = 0;
                    this.updateMessage();
                }
                this.active = false;
                return;
            }
            this.active = true;
            // seek 提交后的豁免窗口：FFmpeg 跳转（HLS 重拉分片）可达数秒，
            // 期间反向同步会把滑块拉回旧位置造成"回弹"，故冻结在用户选择的位置
            boolean seekGrace = System.currentTimeMillis() - lastSeekSentAt < SEEK_SYNC_GRACE_MS;
            if (!this.dragging && !seekGrace) {
                this.value = (double) currentMs() / dur;
                this.updateMessage();
            }
        }

        @Override
        protected void updateMessage() {
            long dur = durationMs();
            long target = (long) (this.value * dur);
            this.setMessage(Component.literal(
                KazumiMessages.formatMs(target) + " / " + KazumiMessages.formatMs(dur)));
        }

        @Override
        protected void applyValue() {
            pendingSeekMs = (long) (this.value * durationMs());
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.dragging = true;
            super.onClick(event, doubleClick);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            if (this.dragging && pendingSeekMs >= 0) {
                sendControl("seek_goto", pendingSeekMs);
                lastSeekSentAt = System.currentTimeMillis();
                pendingSeekMs = -1;
            }
            this.dragging = false;
            super.onRelease(event);
        }
    }
}
