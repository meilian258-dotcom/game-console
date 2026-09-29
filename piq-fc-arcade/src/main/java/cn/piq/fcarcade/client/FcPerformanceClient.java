package cn.piq.fcarcade.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Client-only, opt-in view of existing FC workers. No network requests or file output. */
@EventBusSubscriber(modid="piq_fc_arcade", value=Dist.CLIENT)
public final class FcPerformanceClient {
    private static boolean hud, sampling;
    private static int ticks;
    private static String selected;
    private static List<FcPerformanceView.Entry> entries = List.of();
    private FcPerformanceClient() {}
    static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fc-debug")
                .executes(c -> { FcPerformanceScreen.open(null); return 1; })
                .then(Commands.literal("on").executes(c -> { hud(true); message("FC 性能悬浮窗已开启；约 1 秒后显示数据。"); return 1; }))
                .then(Commands.literal("off").executes(c -> { hud(false); message("FC 性能悬浮窗已关闭。"); return 1; }))
                .then(Commands.literal("status").executes(c -> { refresh(); for (String line : lines()) message(line); return 1; })));
    }
    private static void message(String text) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(text), false);
    }
    static boolean hud() { return hud; }
    static void hud(boolean value) { hud = value; refresh(); }
    static void refresh() {
        var mc = Minecraft.getInstance();
        boolean enabled = mc.level != null && mc.getConnection() != null && (hud || mc.screen instanceof FcPerformanceScreen);
        if (!enabled && !sampling) return;
        sampling = enabled;
        var next = new ArrayList<>(ClientArcadeEvents.performanceEntries(enabled));
        var privateEntry = PrivateHomeClient.performance(enabled);
        if (privateEntry != null) next.add(privateEntry);
        next.sort(Comparator.comparing((FcPerformanceView.Entry e) -> e.device().role())
                .thenComparingDouble(e -> e.device().distanceSquared()).thenComparing(FcPerformanceView.Entry::key));
        entries = enabled ? List.copyOf(next) : List.of();
        if (entries.stream().noneMatch(e -> e.key().equals(selected))) selected = entries.isEmpty() ? null : entries.getFirst().key();
    }
    static int count() { return entries.size(); }
    static void next() {
        if (entries.isEmpty()) return;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).key().equals(selected)) {
            selected = entries.get((i + 1) % entries.size()).key(); return;
        }
    }
    static void prefer(net.minecraft.core.BlockPos pos) {
        if (pos == null) return;
        entries.stream().filter(e -> e.device().name().endsWith("@ " + pos.toShortString())).findFirst().ifPresent(e -> selected = e.key());
    }
    static List<String> lines() {
        if (!sampling) return List.of("性能监控已关闭；输入 /fc-debug on 开启。");
        return FcPerformanceView.lines(entries.stream().filter(e -> e.key().equals(selected)).findFirst().orElse(null));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) { if (++ticks % 5 == 0) refresh(); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        hud = false; ClientArcadeEvents.performanceEntries(false); PrivateHomeClient.performance(false);
        sampling = false; entries = List.of(); selected = null;
    }
    @SubscribeEvent public static void render(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!hud || mc.level == null || mc.player == null || mc.screen != null || mc.options.hideGui) return;
        var g = event.getGuiGraphics(); var lines = lines(); int w = 0;
        for (String line : lines) w = Math.max(w, mc.font.width(line));
        w = Math.max(1, Math.min(w, g.guiWidth() - 18));
        g.fill(5, 5, w + 13, 20 + lines.size() * 11, 0xBB000000);
        g.drawString(mc.font, "FC 性能 · 本机 / 近 1 秒", 9, 9, 0x80DFFF, true);
        int y = 22;
        for (String line : lines) { g.drawString(mc.font, mc.font.plainSubstrByWidth(line, w), 9, y, 0xFFFFFF, true); y += 11; }
    }
}
