package me.zuogeren.kazumiplayer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.util.List;

/**
 * GUI 通用行列表（AbstractSelectionList 最小封装）。
 * 支持三种行：单文本行（搜索结果）、横向网格行（选集按钮阵列）、
 * 带尾缀原版按钮格与整行背景色的行（直链队列的 插队/删除）。
 */
public class SimpleList extends AbstractSelectionList<SimpleList.Row> {

    /** 网格行中的一个可点单元 */
    public record Cell(String text, Runnable onClick) {}

    private static final WidgetSprites BUTTON_SPRITES = new WidgetSprites(
        Identifier.withDefaultNamespace("widget/button"),
        Identifier.withDefaultNamespace("widget/button_disabled"),
        Identifier.withDefaultNamespace("widget/button_highlighted")
    );

    public SimpleList(Minecraft mc, int width, int height, int y, int rowHeight) {
        super(mc, width, height, y, rowHeight);
    }

    public void addRow(Component text, int color, Runnable onClick) {
        this.addEntry(new Row(List.of(new Cell(text.getString(), onClick)), color));
    }

    /** 横向网格行：cells 均分行宽，逐格绘制与点击 */
    public void addGridRow(List<Cell> cells) {
        this.addEntry(new Row(cells, -1));
    }

    /**
     * 带尾缀操作格的行：主格文本均分扣除尾格后的剩余宽度，
     * tailCells 从行右缘起依次排列、各占 tailCellWidth 并按原版按钮外观绘制；
     * onClick 为 null 的尾格不显示。rowBgColor 非 0 时整行填充背景色。
     * 主格需双击才触发 onClick（队列切播防误触）；尾缀按钮始终单击生效。
     */
    public void addRowWithTail(Component text, int color, int rowBgColor, Runnable onClick,
            List<Cell> tailCells, int tailCellWidth) {
        this.addEntry(new Row(List.of(new Cell(text.getString(), onClick)), color,
            rowBgColor, List.copyOf(tailCells), tailCellWidth, true));
    }

    /** 双击行：单击仅选中高亮并回调 onSelect，双击才回调 onActivate（重操作防误触） */
    public void addDoubleClickableRow(Component text, int color, Runnable onSelect, Runnable onActivate) {
        this.addEntry(new Row(List.of(new Cell(text.getString(), onSelect)), color,
            0, List.of(), 0, false, onSelect, onActivate));
    }

    /** 清除所有行的选中高亮（切换选中项时由调用方在回调内先行调用） */
    public void clearRowHighlights() {
        for (Row r : this.children()) r.selectedCell = -1;
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    /**
     * 原版默认把滚动条画在 getRowRight() 之外（列表右缘外侧）；
     * 本列表占满面板宽度，收回右缘内侧对齐边框。
     */
    @Override
    protected int scrollBarX() {
        return this.getX() + this.width - this.scrollbarWidth() - 2;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        // 简单文本行无需旁白详情
    }

    /** 父类默认框整行；改为委托给 Row 按格绘制（网格行只框选中的格） */
    @Override
    protected void extractSelection(GuiGraphicsExtractor graphics, Row entry, int outlineColor) {
        entry.extractCellSelection(graphics, outlineColor);
    }

    public static class Row extends AbstractSelectionList.Entry<Row> {
        private static final int PAD = 4;
        /** 悬停 tooltip 的换行宽度（px） */
        private static final int TOOLTIP_WRAP = 200;

        private final List<Cell> cells;
        private final int color;
        private final int rowBgColor;         // 整行背景色，0 = 无
        private final List<Cell> tailCells;   // 尾缀操作格，空列表表示普通行
        private final int tailCellWidth;
        private final boolean mainNeedsDoubleClick; // 主格双击才触发（队列切播防误触）
        private final Runnable onSelect;      // 单击选中回调（与 onActivate 配对；可空）
        private final Runnable onActivate;    // 双击激活回调（重操作，可空）
        private int selectedCell = -1;

        public Row(List<Cell> cells, int color) {
            this(cells, color, 0, List.of(), 0, false);
        }

        public Row(List<Cell> cells, int color, int rowBgColor, List<Cell> tailCells,
                int tailCellWidth, boolean mainNeedsDoubleClick) {
            this(cells, color, rowBgColor, tailCells, tailCellWidth, mainNeedsDoubleClick, null, null);
        }

        public Row(List<Cell> cells, int color, int rowBgColor, List<Cell> tailCells,
                int tailCellWidth, boolean mainNeedsDoubleClick, Runnable onSelect, Runnable onActivate) {
            this.cells = cells;
            this.color = color;
            this.rowBgColor = rowBgColor;
            this.tailCells = tailCells;
            this.tailCellWidth = tailCellWidth;
            this.mainNeedsDoubleClick = mainNeedsDoubleClick;
            this.onSelect = onSelect;
            this.onActivate = onActivate;
        }

        /** 主格宽度：行宽扣除左右 PAD 与尾缀格后均分 */
        private int cellWidth() {
            int usable = this.getWidth() - PAD * 2 - this.tailCellWidth * this.tailCells.size();
            return Math.max(1, usable / Math.max(1, this.cells.size()));
        }

        /** 尾缀区左缘 X；无尾缀时无意义 */
        private int tailX0() {
            return this.getX() + this.getWidth() - PAD - this.tailCellWidth * this.tailCells.size();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            if (this.rowBgColor != 0) {
                graphics.fill(this.getX() - 2, this.getY(),
                    this.getX() + this.getWidth() + 2, this.getY() + this.getHeight(), this.rowBgColor);
            }
            int textY = this.getY() + (this.getHeight() - mc.font.lineHeight) / 2;
            int cellW = this.cellWidth();
            for (int i = 0; i < this.cells.size(); i++) {
                int cx = this.getX() + PAD + i * cellW;
                String s = mc.font.plainSubstrByWidth(this.cells.get(i).text(), cellW - 6);
                graphics.text(mc.font, Component.literal(s), cx, textY, this.color);
                // 悬停且文本被截断时以 tooltip 显示全文
                if (hovered && mouseX >= cx && mouseX < cx + cellW
                        && mouseY >= this.getY() && mouseY < this.getY() + this.getHeight()
                        && mc.font.width(this.cells.get(i).text()) > cellW - 6) {
                    graphics.setTooltipForNextFrame(mc.font,
                        mc.font.split(Component.literal(this.cells.get(i).text()), TOOLTIP_WRAP),
                        mouseX, mouseY);
                }
            }
            for (int i = 0; i < this.tailCells.size(); i++) {
                Cell cell = this.tailCells.get(i);
                if (cell.onClick() == null) continue; // 不可操作项不渲染按钮（如当前播放项）
                int cx = this.tailX0() + i * this.tailCellWidth;
                boolean btnHovered = mouseX >= cx && mouseX < cx + this.tailCellWidth
                    && mouseY >= this.getY() && mouseY < this.getY() + this.getHeight();
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                    BUTTON_SPRITES.get(true, btnHovered),
                    cx, this.getY(), this.tailCellWidth, this.getHeight(), ARGB.white(1.0F));
                String s = mc.font.plainSubstrByWidth(cell.text(), this.tailCellWidth - 4);
                int tx = cx + Math.max(0, (this.tailCellWidth - mc.font.width(s)) / 2);
                graphics.text(mc.font, Component.literal(s), tx, textY, -1);
            }
        }

        /** 只框住选中的格（左右各留 2px 间隙），而非整行 */
        private void extractCellSelection(GuiGraphicsExtractor graphics, int outlineColor) {
            if (this.selectedCell < 0 || this.selectedCell >= this.cells.size()) return;
            int cellW = this.cellWidth();
            int x0 = this.getX() + PAD + this.selectedCell * cellW;
            int y0 = this.getY();
            int x1 = x0 + cellW - 2;
            int y1 = y0 + this.getHeight();
            graphics.fill(x0, y0, x1, y1, outlineColor);
            graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, -16777216);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            // 先判界再取模，避免负坐标整除回绕误中首格
            double mx = event.x();
            if (!this.tailCells.isEmpty()) {
                int tailX0 = this.tailX0();
                if (mx >= tailX0 && mx < tailX0 + this.tailCellWidth * this.tailCells.size()) {
                    Cell cell = this.tailCells.get((int) ((mx - tailX0) / this.tailCellWidth));
                    if (cell.onClick() != null) cell.onClick().run();
                    return true;
                }
            }
            int cellW = this.cellWidth();
            if (mx < this.getX() + PAD || mx >= this.getX() + PAD + cellW * this.cells.size()) return false;
            int idx = (int) ((mx - this.getX() - PAD) / cellW);
            // 双击行：双击触发重操作；单击仅选中高亮（回调内可刷新列表/显示详情）
            if (doubleClick && this.onActivate != null) {
                this.onActivate.run();
                this.selectedCell = idx;
                return true;
            }
            if (this.onSelect != null && !doubleClick) {
                this.onSelect.run();
                this.selectedCell = idx;
                return true;
            }
            Runnable onClick = this.cells.get(idx).onClick();
            if (onClick == null) return false;
            if (this.mainNeedsDoubleClick && !doubleClick) return true; // 单击吞掉不触发，防误触
            this.selectedCell = idx;
            onClick.run();
            return true;
        }
    }
}
