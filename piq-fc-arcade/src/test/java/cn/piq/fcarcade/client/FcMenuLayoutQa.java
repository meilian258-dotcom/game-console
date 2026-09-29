package cn.piq.fcarcade.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Headless geometry QA using the real layout. Not Minecraft rendering, textures, fonts or screenshots. */
public final class FcMenuLayoutQa {
    private FcMenuLayoutQa() {}
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path output = Path.of(args.length == 0 ? "design/fc-menu-alpha3-qa" : args[0]);
        Files.createDirectories(output);
        StringBuilder report = new StringBuilder("view\tviewport\tpanel_xywh\trows\tsplit\tcompact_split\n");
        for (int[] size : new int[][]{{320, 240}, {512, 278}, {640, 360}, {1024, 556}}) {
            for (String view : List.of("library", "cartridge-roms", "cartridge-covers")) {
                boolean library = view.equals("library"), covers = view.equals("cartridge-covers");
                var layout = library ? FcMenuLayout.library(size[0], size[1]) : FcMenuLayout.cartridge(size[0], size[1], covers);
                var image = render(size[0], size[1], layout, library, covers);
                String name = view + "-" + size[0] + "x" + size[1];
                ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
                ImageIO.write(image, "jpg", output.resolve(name + ".jpg").toFile());
                var p = layout.panel();
                report.append(view).append('\t').append(size[0]).append('x').append(size[1]).append('\t')
                        .append(p.x()).append(',').append(p.y()).append(',').append(p.width()).append(',').append(p.height())
                        .append('\t').append(layout.rows()).append('\t').append(layout.split()).append('\t').append(layout.compactSplit()).append('\n');
            }
        }
        Files.writeString(output.resolve("geometry.tsv"), report);
        System.out.println("Exported 12 actual-layout QA PNG/JPEG pairs to " + output.toAbsolutePath());
    }

    private static BufferedImage render(int width, int height, FcMenuLayout.Layout layout, boolean library, boolean covers) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        g.setColor(new Color(0x282D35)); g.fillRect(0, 0, width, height);
        text(g, "GEOMETRY QA / NOT A MINECRAFT SCREENSHOT", 3, 0, width - 6, 0x8B96A8);
        var p = layout.panel();
        box(g, p, 0x111A29, 0x47566F);
        box(g, layout.list(), 0x0C1420, 0x243146);
        if (layout.details().width() > 0) box(g, layout.details(), 0x101927, 0x243146);
        text(g, library ? "FC ROM LIBRARY" : "FC CARTRIDGE EDITOR / OP WRITE", p.x() + 10, p.y() + (layout.compactSplit() ? 10 : 12), p.width() - 20, 0xFFFFFF);
        for (int i = 0; i < layout.rows(); i++) button(g, layout.row(i), "Sample long game or cover filename " + (i + 1) + " ...");
        int x = p.x() + 10, content = p.width() - 20;
        var detail = layout.details();
        if (library) {
            text(g, "Page 1 / N - select a game, then use its actions", x, p.y() + (layout.compactSplit() ? 24 : 28), content, 0xACBED2);
            text(g, "Selected long game filename", detail.x(), detail.y(), detail.width(), 0xD6ECFF);
            if (layout.split() && !layout.compactSplit()) {
                text(g, "Server / local status", detail.x(), detail.y() + 20, detail.width(), 0xAAB8C8);
                text(g, "SHA 0123456789ab", detail.x(), detail.y() + 36, detail.width(), 0x8191A7);
                text(g, "Hover full names / server-validated actions", detail.x(), detail.y() + 54, detail.width(), 0x8191A7);
            }
            String[] actions = {"Use / Upload", "Save mode", "1 / 2 players", "Rename", "Delete (confirmation)"};
            var rectangles = FcMenuLayout.libraryActions(layout);
            for (int i = 0; i < actions.length; i++) button(g, rectangles.get(i), actions[i]);
            text(g, "ROM uploads are checked by the server", x, layout.footerY() + (layout.compactSplit() ? 2 : 6), content, 0xE0B060);
            buttons(g, FcMenuLayout.columns(x, FcMenuLayout.libraryUpperFooterY(layout), content, 4), "Prev", "Next", "Leaderboard", "ROM folder");
            buttons(g, FcMenuLayout.columns(x, FcMenuLayout.libraryLowerFooterY(layout), content, 5), "Refresh", "Saves", "Settings", "Skins", "Done");
        } else {
            button(g, new FcMenuLayout.Rect(x, p.y() + 30, content - 96, 20), "Editable cartridge title");
            button(g, new FcMenuLayout.Rect(x + content - 92, p.y() + 30, 92, 20), "Save title");
            buttons(g, FcMenuLayout.columns(x, p.y() + 54, content, 3), "Games", "Local covers", "Refresh");
            if (covers) buttons(g, FcMenuLayout.columns(x, p.y() + 78, content, 2), "Clear / default cover", "Restore opening cover");
            buttons(g, FcMenuLayout.columns(x, layout.footerY(), content, 2), "Open ROM folder", "Open cover folder");
            buttons(g, FcMenuLayout.columns(x, layout.footerY() + 24, content, 3), "Previous page", "Next page", "Close / cancel upload");
            text(g, "Page 1 / N - upload / scan status", x, layout.footerY() + 50, content, 0x8AD8E7);
            text(g, "Keep this card in its bound hand and slot", x, layout.footerY() + 64, content, 0x9AA5B8);
            if (layout.split()) {
                for (int i = 0; i < 7; i++) {
                    int[] offsets = {0, 20, 42, 58, 82, 106, 130};
                    String[] labels = {covers ? "Cover write" : "ROM write", "Selected filename", "Click writes to the held card",
                            covers ? "Centered 512x256 cover" : "Local ROM requires upload", "SHA 0123456789ab", covers ? "piq-fc/covers" : "piq-fc/roms", "Hover for the full filename"};
                    text(g, labels[i], detail.x(), detail.y() + offsets[i], detail.width(), 0xAAB8C8);
                }
            }
        }
        g.dispose();
        return image;
    }
    private static void buttons(Graphics2D g, List<FcMenuLayout.Rect> rectangles, String... labels) {
        for (int i = 0; i < labels.length; i++) button(g, rectangles.get(i), labels[i]);
    }
    private static void button(Graphics2D g, FcMenuLayout.Rect r, String label) {
        box(g, r, 0x34455D, 0x63758E);
        text(g, label, r.x() + 4, r.y() + 5, r.width() - 8, 0xE7EDF7);
    }
    private static void box(Graphics2D g, FcMenuLayout.Rect r, int fill, int border) {
        g.setColor(new Color(fill)); g.fillRect(r.x(), r.y(), r.width(), r.height());
        g.setColor(new Color(border)); g.drawRect(r.x(), r.y(), r.width() - 1, r.height() - 1);
    }
    private static void text(Graphics2D g, String value, int x, int y, int width, int color) {
        var metrics = g.getFontMetrics();
        if (metrics.stringWidth(value) > width) {
            while (!value.isEmpty() && metrics.stringWidth(value + "...") > width) value = value.substring(0, value.length() - 1);
            value += "...";
        }
        g.setColor(new Color(color)); g.drawString(value, x, y + metrics.getAscent());
    }
}
