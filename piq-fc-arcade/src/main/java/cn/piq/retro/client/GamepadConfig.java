package cn.piq.retro.client;

import cn.piq.retro.input.InputProfile;
import cn.piq.retro.input.StickDeadzone;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable local-only settings; no device activation or disk writes in this value object. */
public record GamepadConfig(boolean enabled, String deviceKey, float deadzoneEnter, float deadzoneExit,
                            Map<String, InputProfile> profiles) {
    public static final Set<String> SYSTEMS = Set.of("NES", "SFC", "ARCADE");
    public GamepadConfig {
        Objects.requireNonNull(deviceKey); Objects.requireNonNull(profiles);
        if (deviceKey.length() > 256 || deviceKey.indexOf('\n') >= 0 || deviceKey.indexOf('\r') >= 0) throw new IllegalArgumentException("设备标识无效");
        new StickDeadzone(deadzoneEnter, deadzoneExit);
        if (deadzoneEnter < .05f || deadzoneEnter > .75f) throw new IllegalArgumentException("死区范围为 5%—75%");
        if (!profiles.keySet().equals(SYSTEMS)) throw new IllegalArgumentException("缺少系统按键配置");
        profiles = Map.copyOf(profiles);
    }
    public static GamepadConfig defaults() {
        return new GamepadConfig(true, "", .25f, .18f, Map.of("NES", InputProfile.defaults(), "SFC", InputProfile.defaults(), "ARCADE", InputProfile.defaults()));
    }
    public GamepadConfig enabled(boolean value) { return new GamepadConfig(value, deviceKey, deadzoneEnter, deadzoneExit, profiles); }
    public GamepadConfig device(String value) { return new GamepadConfig(enabled, value, deadzoneEnter, deadzoneExit, profiles); }
    public GamepadConfig deadzone(float enter, float exit) { return new GamepadConfig(enabled, deviceKey, enter, exit, profiles); }
    public GamepadConfig profile(String system, InputProfile profile) {
        if (!SYSTEMS.contains(system)) throw new IllegalArgumentException("Unknown system");
        var copy = new LinkedHashMap<>(profiles); copy.put(system, Objects.requireNonNull(profile));
        return new GamepadConfig(enabled, deviceKey, deadzoneEnter, deadzoneExit, copy);
    }
}
