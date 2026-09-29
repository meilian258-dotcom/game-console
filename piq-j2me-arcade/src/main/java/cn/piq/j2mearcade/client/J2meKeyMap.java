package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.core.MicroEmuHeadlessSession;
import org.lwjgl.glfw.GLFW;

final class J2meKeyMap {
    private J2meKeyMap() {
    }

    static MicroEmuHeadlessSession.Key map(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> MicroEmuHeadlessSession.Key.UP;
            case GLFW.GLFW_KEY_DOWN -> MicroEmuHeadlessSession.Key.DOWN;
            case GLFW.GLFW_KEY_LEFT -> MicroEmuHeadlessSession.Key.LEFT;
            case GLFW.GLFW_KEY_RIGHT -> MicroEmuHeadlessSession.Key.RIGHT;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_SPACE -> MicroEmuHeadlessSession.Key.FIRE;
            case GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_Q -> MicroEmuHeadlessSession.Key.SOFT_LEFT;
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_E -> MicroEmuHeadlessSession.Key.SOFT_RIGHT;
            case GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> MicroEmuHeadlessSession.Key.NUM_0;
            case GLFW.GLFW_KEY_1 -> MicroEmuHeadlessSession.Key.NUM_1;
            case GLFW.GLFW_KEY_2 -> MicroEmuHeadlessSession.Key.NUM_2;
            case GLFW.GLFW_KEY_3 -> MicroEmuHeadlessSession.Key.NUM_3;
            case GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_KP_4 -> MicroEmuHeadlessSession.Key.NUM_4;
            case GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_KP_5 -> MicroEmuHeadlessSession.Key.NUM_5;
            case GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_KP_6 -> MicroEmuHeadlessSession.Key.NUM_6;
            case GLFW.GLFW_KEY_7 -> MicroEmuHeadlessSession.Key.NUM_7;
            case GLFW.GLFW_KEY_8 -> MicroEmuHeadlessSession.Key.NUM_8;
            case GLFW.GLFW_KEY_9 -> MicroEmuHeadlessSession.Key.NUM_9;
            // Physical numpad positions are converted to a phone keypad.
            case GLFW.GLFW_KEY_KP_7 -> MicroEmuHeadlessSession.Key.NUM_1;
            case GLFW.GLFW_KEY_KP_8 -> MicroEmuHeadlessSession.Key.NUM_2;
            case GLFW.GLFW_KEY_KP_9 -> MicroEmuHeadlessSession.Key.NUM_3;
            case GLFW.GLFW_KEY_KP_1 -> MicroEmuHeadlessSession.Key.NUM_7;
            case GLFW.GLFW_KEY_KP_2 -> MicroEmuHeadlessSession.Key.NUM_8;
            case GLFW.GLFW_KEY_KP_3 -> MicroEmuHeadlessSession.Key.NUM_9;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> MicroEmuHeadlessSession.Key.STAR;
            case GLFW.GLFW_KEY_KP_DIVIDE -> MicroEmuHeadlessSession.Key.POUND;
            default -> null;
        };
    }
}
