package cn.piq.fcarcade.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Local panel paint and bounded text; no blur, postprocess, world or file access. */
final class FcMenuUi {
    private FcMenuUi() {}
    static void backdrop(GuiGraphics graphics, FcMenuLayout.Layout layout, int width, int height) {
        graphics.fill(0, 0, width, height, 0x88000000);
        var p = layout.panel();
        graphics.fill(p.x() - 1, p.y() - 1, p.right() + 1, p.bottom() + 1, 0xFF465268);
        graphics.fill(p.x(), p.y(), p.right(), p.bottom(), 0xF5181D28);
        if (layout.supported()) {
            var list = layout.list();
            graphics.fill(list.x() - 2, list.y() - 2, list.right() + 2, list.bottom() + 2, 0xAA10151E);
            if (layout.split()) {
                var detail = layout.details();
                graphics.fill(detail.x() - 4, detail.y() - 2, detail.right() + 2, detail.bottom() + 2, 0xAA10151E);
            }
        }
    }
    static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        if (font.width("…") > width) return font.plainSubstrByWidth(value, width);
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }
    static Button button(Font font, Component text, FcMenuLayout.Rect rect, Runnable action, boolean active) {
        Button button = Button.builder(Component.literal(fit(font, text.getString(), rect.width() - 10)), ignored -> action.run())
                .bounds(rect.x(), rect.y(), rect.width(), rect.height()).tooltip(Tooltip.create(text)).build();
        button.active = active;
        return button;
    }
    static Component line(GuiGraphics graphics, Font font, Component text, int x, int y, int width,
                          int color, int mouseX, int mouseY) {
        String full = text.getString();
        graphics.drawString(font, fit(font, full, width), x, y, color, false);
        return font.width(full) > width && mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + font.lineHeight ? text : null;
    }
}
