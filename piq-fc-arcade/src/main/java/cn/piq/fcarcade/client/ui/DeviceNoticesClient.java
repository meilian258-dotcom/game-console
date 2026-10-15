// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Presentation boundary only. No network payloads, invitations or other mods' chat are intercepted. */
@EventBusSubscriber(modid="piq_fc_arcade", value=Dist.CLIENT)
public final class DeviceNoticesClient {
    private static final DeviceNoticeChat CHAT = new DeviceNoticeChat();
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
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var entry = CHAT.poll(DeviceNotices.snapshot(), System.nanoTime());
        if (entry == null) return;
        // Local chat only: do not send diagnostics, paths or stack traces to the server.
        mc.gui.getChat().addMessage(Component.literal(DeviceNoticeChat.line(entry)).withStyle(ChatFormatting.YELLOW));
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        CHAT.discardThrough(DeviceNotices.last());
    }
}
