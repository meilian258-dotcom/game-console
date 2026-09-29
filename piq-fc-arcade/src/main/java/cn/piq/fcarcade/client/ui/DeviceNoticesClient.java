package cn.piq.fcarcade.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Presentation boundary only. No network payloads, invitations or other mods' chat are intercepted. */
@EventBusSubscriber(modid="piq_fc_arcade", value=Dist.CLIENT)
public final class DeviceNoticesClient {
    private static final SystemToast.SystemToastId ERROR_TOAST = new SystemToast.SystemToastId(6500);
    private static long shown;
    private DeviceNoticesClient() {}
    public static void message(String device, String text) {
        if (!DeviceNoticePolicy.routineText(text)) DeviceNotices.record(device, text);
    }
    public static void message(String device, Component text) {
        if (text == null) return;
        if (text.getContents() instanceof TranslatableContents translated && DeviceNoticePolicy.routineKey(translated.getKey())) return;
        message(device, text.getString());
    }
    /** Explicit, cancellable transfers/invitation progress keep their existing workflow feedback. */
    public static void workflow(String text) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && text != null && !text.isBlank() && !DeviceNoticePolicy.routineText(text)) mc.player.displayClientMessage(Component.literal(text), true);
    }
    @SubscribeEvent public static void systemMessage(ClientChatReceivedEvent.System event) {
        // This event type excludes player chat. Unknown/command feedback and composed messages stay visible.
        var message = event.getMessage();
        if (!message.getSiblings().isEmpty()) return;
        if (message.getContents() instanceof TranslatableContents translated) {
            if (DeviceNoticePolicy.routineKey(translated.getKey())) event.setCanceled(true);
            else if (event.isOverlay() && DeviceNoticePolicy.errorKey(translated.getKey())) {
                DeviceNotices.record("设备", message.getString()); event.setCanceled(true);
            }
        } else if (event.isOverlay() && !message.getString().isBlank()) {
            if (DeviceNoticePolicy.routineText(message.getString())) event.setCanceled(true);
            else if (DeviceNoticePolicy.errorText(message.getString())) {
                DeviceNotices.record("设备",message.getString()); event.setCanceled(true);
            }
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var entry = DeviceNotices.last();
        var mc = Minecraft.getInstance();
        if (entry == null || entry.id() <= shown || mc.player == null) return;
        shown = entry.id();
        // One dedicated vanilla toast, reused instead of queuing a wall of repeated failures.
        String fullTitle = entry.device() + "：" + entry.summary();
        String title = mc.font.plainSubstrByWidth(fullTitle, 192);
        if (title.length() < fullTitle.length()) title = mc.font.plainSubstrByWidth(fullTitle, 184) + "…";
        Component heading = Component.literal(title), guide = Component.literal("模组设置 → 运行环境");
        var existing = mc.getToasts().getToast(SystemToast.class, ERROR_TOAST);
        if (existing == null) mc.getToasts().addToast(SystemToast.multiline(mc, ERROR_TOAST, heading, guide));
        else existing.reset(heading, guide);
    }
}
