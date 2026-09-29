package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.DeviceConfirmScreen;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Dedicated type permits only FC's deletion confirmation to opt out of optional menu blur. */
final class FcRomDeleteScreen extends DeviceConfirmScreen {
    FcRomDeleteScreen(Consumer<Boolean> callback, Component title, Component message, Component confirm, Component cancel) {
        super(value -> callback.accept(value), title, message, confirm, cancel);
    }
}
