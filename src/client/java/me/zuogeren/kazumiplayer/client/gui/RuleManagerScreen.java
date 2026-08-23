package me.zuogeren.kazumiplayer.client.gui;

import com.google.gson.reflect.TypeToken;
import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.network.gui.GuiProtocol;
import me.zuogeren.kazumiplayer.network.packet.GuiActionPacket;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则管理器界面：远程仓库规则列表 / 拉取 / 删除 / 连通性延迟测试 / 弃用检测。
 * 操作经 GuiProtocol 通用通道发往服务端（rule_list/pull/delete/test），
 * 结果经 GuiClientState 回推（rule_list / rule_test_result / error / play_ok）。
 * 行交互：已安装规则 [测][删]，未安装规则 [装]；「测试全部」逐个请求已安装规则。
 */
public class RuleManagerScreen extends Screen implements GuiClientState.Listener {

    private static final int ROW_HEIGHT = 18;
    private static final int TAIL_W = 20;
    private static final long STATUS_TOAST_MS = 6000;

    private List<GuiPayloads.RuleListEntryPayload> entries = List.of();
    /** 连通性测试结果：name -> 延迟ms（失败不放入，用 failed 区分展示） */
    private final Map<String, Long> latencies = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final Set<String> testing = new HashSet<>();
    private String statusTitle = "";
    private long statusTitleAt;

    private SimpleList ruleList;

    public RuleManagerScreen() {
        super(Component.literal("规则管理器"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int w = this.width, h = this.height;
        int listBottom = h - 36;

        this.addRenderableWidget(Button.builder(Component.literal("刷新列表"), b -> refreshList())
            .bounds(6, 6, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("测试全部"), b -> testAll())
            .bounds(70, 6, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose())
            .bounds(w - 52, 6, 46, 18).build());

        this.ruleList = new SimpleList(this.minecraft, w - 12, listBottom - 42, 42, ROW_HEIGHT);
        this.ruleList.setX(6);
        this.addRenderableWidget(this.ruleList);

        GuiClientState.setListener(this);
        refreshList();
    }

    @Override
    public void removed() {
        GuiClientState.setListener(null);
        super.removed();
    }

    // ---- 渲染 ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int w = this.width, h = this.height;
        graphics.text(this.font, Component.literal("规则管理器")
            .withStyle(ChatFormatting.BOLD), 8, 28, -1);

        long installed = entries.stream().filter(GuiPayloads.RuleListEntryPayload::installed).count();
        long deprecated = entries.stream().filter(GuiPayloads.RuleListEntryPayload::installed)
            .filter(GuiPayloads.RuleListEntryPayload::deprecated).count();
        String stats = "共 " + entries.size() + " 条 · 已安装 " + installed
            + (deprecated > 0 ? " · 已弃用 " + deprecated : "");
        graphics.text(this.font, Component.literal(stats).withStyle(ChatFormatting.GRAY),
            8 + this.font.width("规则管理器") + 12, 28, -1);

        if (!statusTitle.isEmpty() && System.currentTimeMillis() - statusTitleAt < STATUS_TOAST_MS) {
            String t = "» " + statusTitle;
            int tw = this.font.width(t);
            int y = h - 26;
            graphics.fill(5, y, 12 + tw + 6, y + 14, 0xE0101008);
            graphics.fill(5, y, 9, y + 14, 0xFFFFC94A);
            graphics.text(this.font,
                Component.literal(t).withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.YELLOW),
                14, y + 3, -1);
        }
    }

    private void setStatus(String text) {
        this.statusTitle = text == null ? "" : text;
        this.statusTitleAt = System.currentTimeMillis();
    }

    // ---- 数据回推 ----

    @Override
    public void onGuiData(String dataType, String json) {
        switch (dataType) {
            case GuiProtocol.DATA_RULE_LIST -> {
                entries = GuiPayloads.fromJson(json,
                    new TypeToken<List<GuiPayloads.RuleListEntryPayload>>() {}.getType());
                if (entries == null) entries = List.of();
                testing.removeIf(name -> entries.stream().noneMatch(e -> e.name().equals(name) && e.installed()));
                rebuildList();
                // 不设状态提示：pull/delete 成功后的自动回推会频繁触发，
                // 「已加载」会顶掉真正的操作反馈（操作结果由 DATA_PLAY_OK 承担）
            }
            case GuiProtocol.DATA_RULE_TEST_RESULT -> {
                var r = GuiPayloads.fromJson(json, GuiPayloads.RuleTestResultPayload.class);
                if (r == null) return;
                testing.remove(r.name());
                if (r.ok()) {
                    latencies.put(r.name(), r.latency());
                    failed.remove(r.name());
                } else {
                    latencies.remove(r.name());
                    failed.add(r.name());
                }
                rebuildList();
                setStatus(r.ok() ? r.name() + " 连通正常，延迟 " + r.latency() + "ms" : r.name() + " 连通失败");
            }
            case GuiProtocol.DATA_PLAY_OK -> {
                var p = GuiPayloads.fromJson(json, GuiPayloads.PlayOkPayload.class);
                setStatus(p != null ? p.title() : "操作完成");
            }
            case GuiProtocol.DATA_ERROR -> {
                var p = GuiPayloads.fromJson(json, GuiPayloads.ErrorPayload.class);
                if (p != null) {
                    setStatus(p.message());
                    KazumiLog.network.warn("Rule manager action failed: {}", p.message());
                }
            }
            default -> {}
        }
    }

    // ---- 列表构建 ----

    private void rebuildList() {
        this.ruleList.clearEntries();
        for (var e : entries) {
            Component text = buildLabel(e);
            List<SimpleList.Cell> tail = new ArrayList<>();
            if (e.installed()) {
                boolean busy = testing.contains(e.name());
                tail.add(new SimpleList.Cell(busy ? "…" : "测", () -> testRule(e.name())));
                tail.add(new SimpleList.Cell("删", () -> deleteRule(e.name())));
                this.ruleList.addRowWithTail(text, -1, 0, null,
                    List.copyOf(tail), TAIL_W * 2);
            } else {
                tail.add(new SimpleList.Cell("装", () -> pullRule(e.name())));
                this.ruleList.addRowWithTail(text, -1, 0, null, List.copyOf(tail), TAIL_W);
            }
        }
        if (entries.isEmpty()) {
            this.ruleList.addRow(Component.literal("（列表为空，点击「刷新列表」获取）")
                .withStyle(ChatFormatting.DARK_GRAY), -1, null);
        }
    }

    private Component buildLabel(GuiPayloads.RuleListEntryPayload e) {
        String s = (e.installed() ? "● " : "○ ") + e.name() + " v"
            + (e.installed() ? e.installedVersion() : e.remoteVersion());
        if (e.installed()) {
            Long lat = latencies.get(e.name());
            if (testing.contains(e.name())) s += " · 测试中…";
            else if (failed.contains(e.name())) s += " · 连通失败";
            else if (lat != null) s += " · " + lat + "ms";
        }
        if (!e.author().isEmpty()) s += " · " + e.author();
        if (e.deprecated()) s += " [已弃用]";
        return e.deprecated()
            ? Component.literal(s).withStyle(ChatFormatting.RED)
            : Component.literal(s);
    }

    // ---- 操作 ----

    private void refreshList() {
        setStatus("正在获取规则列表...");
        sendAction(GuiProtocol.ACTION_RULE_LIST, "{}");
    }

    private void pullRule(String name) {
        setStatus("正在拉取 " + name + " ...");
        sendAction(GuiProtocol.ACTION_RULE_PULL, new GuiPayloads.RuleNamePayload(name));
    }

    private void deleteRule(String name) {
        setStatus("正在删除 " + name + " ...");
        sendAction(GuiProtocol.ACTION_RULE_DELETE, new GuiPayloads.RuleNamePayload(name));
    }

    private void testRule(String name) {
        if (testing.contains(name)) return;
        testing.add(name);
        rebuildList();
        sendAction(GuiProtocol.ACTION_RULE_TEST, new GuiPayloads.RuleNamePayload(name));
    }

    private void testAll() {
        boolean any = false;
        for (var e : entries) {
            if (e.installed() && !testing.contains(e.name())) {
                testing.add(e.name());
                sendAction(GuiProtocol.ACTION_RULE_TEST, new GuiPayloads.RuleNamePayload(e.name()));
                any = true;
            }
        }
        rebuildList();
        setStatus(any ? "正在测试全部已安装规则..." : "没有已安装的规则");
    }

    private void sendAction(String action, Object payloadJson) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            // 规则管理操作与屏幕无关，screenPos 以 ZERO 占位（服务端处理器不读取）
            mc.getConnection().send(new ServerboundCustomPayloadPacket(
                new GuiActionPacket(net.minecraft.core.BlockPos.ZERO, action,
                    payloadJson instanceof String s ? s : GuiPayloads.toJson(payloadJson))));
        } else {
            KazumiLog.network.warn("Rule manager send action '{}' failed: no connection", action);
        }
    }
}
