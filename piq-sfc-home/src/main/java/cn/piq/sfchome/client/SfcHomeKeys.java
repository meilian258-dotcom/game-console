// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.nio.file.*;
import java.io.*;

/** Legacy SFC mappings remain configurable; gameplay never rewrites Minecraft bindings. */
final class SfcHomeKeys {
    private static final IKeyConflictContext CONTEXT=new IKeyConflictContext() {
        public boolean isActive() { return SfcHomeClient.acceptsInput(); }
        public boolean conflicts(IKeyConflictContext other) { return other==this; }
    };
    static final KeyMapping[] KEYS={key("b",GLFW.GLFW_KEY_J),key("y",GLFW.GLFW_KEY_U),
            key("select",GLFW.GLFW_KEY_BACKSPACE),key("start",GLFW.GLFW_KEY_ENTER),
            key("up",GLFW.GLFW_KEY_UP),key("down",GLFW.GLFW_KEY_DOWN),key("left",GLFW.GLFW_KEY_LEFT),key("right",GLFW.GLFW_KEY_RIGHT),
            key("a",GLFW.GLFW_KEY_K),key("x",GLFW.GLFW_KEY_I),key("l",GLFW.GLFW_KEY_Q),key("r",GLFW.GLFW_KEY_E)};
    private static boolean recovered;
    private SfcHomeKeys() {}
    static void register(RegisterKeyMappingsEvent e) { for(var key:KEYS)e.register(key); }
    private static KeyMapping key(String name,int code) { return new KeyMapping("key.piq_sfc_home."+name,CONTEXT,InputConstants.Type.KEYSYM,code,"key.categories.piq_sfc_home"); }
    static int poll() {
        if(!SfcHomeClient.acceptsInput()) return 0;
        long window=Minecraft.getInstance().getWindow().getWindow(); int mask=0;
        for(int bit=0;bit<KEYS.length;bit++) {
            var key=KEYS[bit].getKey(); int code=key.getValue();
            boolean down=code>=0 && (key.getType()==InputConstants.Type.MOUSE
                    ? GLFW.glfwGetMouseButton(window,code)==GLFW.GLFW_PRESS
                    : key.getType()==InputConstants.Type.KEYSYM && InputConstants.isKeyDown(window,code));
            if(down)mask|=1<<bit;
        }
        return mask;
    }
    static boolean mouseBound(int button) { return Arrays.stream(KEYS).anyMatch(k->k.getKey().getType()==InputConstants.Type.MOUSE&&k.getKey().getValue()==button); }
    static void sync(boolean active) {
        recover();
        if(!active)restore();
    }
    static void restore() {
        for(var k:KEYS)clear(k);
    }
    private static void clear(KeyMapping key) { key.setDown(false);while(key.consumeClick()) {} }
    private static Path path() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config/piq-sfc-home-key-recovery.properties"); }
    /** One-time compatibility repair for an interrupted pre-unified-control session. */
    private static void recover() {
        if(recovered)return;recovered=true;Path p=path();
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))return;
        try(InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)) {
            Properties properties=new Properties();properties.load(in);
            for(var k:Minecraft.getInstance().options.keyMappings) {
                String original=properties.getProperty(k.getName());if(original!=null&&k.isUnbound())k.setKey(InputConstants.getKey(original));
            }
            KeyMapping.resetMapping();Minecraft.getInstance().options.save();Files.deleteIfExists(p);
        }catch(IOException|RuntimeException ignored) { /* Keep recovery journal for next launch. */ }
    }
}
