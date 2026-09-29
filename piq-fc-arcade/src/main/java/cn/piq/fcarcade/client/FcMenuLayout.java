package cn.piq.fcarcade.client;

import java.util.ArrayList;
import java.util.List;

/** GUI-pixel geometry only: never changes the user's GUI scale or accesses Minecraft. */
final class FcMenuLayout {
    static final int MAX_WIDTH = 760, MAX_HEIGHT = 460, ROW_STEP = 24;
    record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(Rect other) {
            return other.x >= x && other.y >= y && other.right() <= right() && other.bottom() <= bottom();
        }
        boolean overlaps(Rect other) {
            return x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y;
        }
    }
    record Layout(Rect panel, Rect list, Rect details, int footerY, int rows, boolean split, boolean compactSplit, boolean supported) {
        Rect row(int index) { return new Rect(list.x, list.y + index * ROW_STEP, list.width, 20); }
    }
    private FcMenuLayout() {}

    static Layout library(int width, int height) { return create(width, height, 46, 74, true); }
    static Layout cartridge(int width, int height, boolean covers) { return create(width, height, covers ? 104 : 78, 78, false); }

    static List<Rect> cartridgeComputerTabs(Layout layout) {
        return columns(layout.panel.x + 10, layout.panel.y + 54, layout.panel.width - 20, 4);
    }

    private static Layout create(int width, int height, int header, int footer, boolean compactDetails) {
        int w = Math.min(MAX_WIDTH, Math.min(Math.max(1, width - 24), Math.max(296, width * 88 / 100)));
        int h = Math.min(MAX_HEIGHT, Math.min(Math.max(1, height - 24), Math.max(216, height * 86 / 100)));
        // At medium sizes preserve the 192px library detail/actions column without filling the window.
        if (compactDetails && w >= 560 && height - 24 >= 312) h = Math.max(h, 312);
        Rect panel = new Rect((width - w) / 2, (height - h) / 2, w, h);
        if (width < 320 || height < 240)
            return new Layout(panel, panel, new Rect(panel.x, panel.y, 0, 0), panel.bottom() - 24, 1, false, false, false);
        boolean compactSplit = compactDetails && w >= 420 && (w < 560 || h - header - footer < 192);
        if (compactSplit) { header = 36; footer = 62; }
        int x = panel.x + 10, y = panel.y + header, bodyWidth = w - 20, bodyHeight = h - header - footer;
        boolean split = compactSplit || w >= 560 && bodyHeight >= (compactDetails ? 192 : 144);
        Rect list, details;
        if (split) {
            int detailWidth = compactSplit ? 176 : 220;
            list = new Rect(x, y, bodyWidth - detailWidth - 12, bodyHeight);
            details = new Rect(list.right() + 12, y, detailWidth, bodyHeight);
        } else if (compactDetails) {
            list = new Rect(x, y, bodyWidth, bodyHeight - 74);
            details = new Rect(x, list.bottom() + 6, bodyWidth, 68);
        } else {
            list = new Rect(x, y, bodyWidth, bodyHeight);
            details = new Rect(x, y, 0, 0);
        }
        // Compact ROM rows need no trailing 4px row gap after the last visible item.
        int rowPixels = list.height + (compactSplit ? 4 : 0);
        return new Layout(panel, list, details, panel.bottom() - footer,
                Math.max(1, Math.min(12, rowPixels / ROW_STEP)), split, compactSplit, true);
    }

    static List<Rect> columns(int x, int y, int width, int count) {
        var columns = new ArrayList<Rect>();
        int available = width - (count - 1) * 4;
        for (int index = 0; index < count; index++) {
            int start = available * index / count, end = available * (index + 1) / count;
            columns.add(new Rect(x + start + index * 4, y, end - start, 20));
        }
        return List.copyOf(columns);
    }

    static List<Rect> libraryActions(Layout layout) {
        Rect detail = layout.details;
        var result = new ArrayList<Rect>();
        if (layout.compactSplit) {
            for (int row = 0; row < 5; row++) result.add(new Rect(detail.x, detail.y + 16 + row * 20, detail.width, 18));
        } else if (layout.split) {
            int y = Math.max(detail.y + 76, detail.bottom() - 116);
            for (int row = 0; row < 5; row++) result.add(new Rect(detail.x, y + row * 24, detail.width, 20));
        } else {
            result.addAll(columns(detail.x, detail.y + 16, detail.width, 3));
            result.addAll(columns(detail.x, detail.y + 40, detail.width, 2));
        }
        return List.copyOf(result);
    }

    static int libraryUpperFooterY(Layout layout) { return layout.footerY + (layout.compactSplit ? 16 : 22); }
    static int libraryLowerFooterY(Layout layout) { return layout.footerY + (layout.compactSplit ? 38 : 46); }

    static int pageCount(int count, int rows) { return Math.max(1, (Math.max(0, count) + rows - 1) / rows); }
    static int clampPage(int page, int count, int rows) { return Math.max(0, Math.min(page, pageCount(count, rows) - 1)); }
}
