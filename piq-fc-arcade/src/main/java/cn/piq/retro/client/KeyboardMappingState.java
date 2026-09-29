package cn.piq.retro.client;

import cn.piq.fcarcade.mixin.KeyMappingStateAccess;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import java.util.function.Predicate;

/** Clears held and queued state, including ToggleKeyMapping, without rebinding. */
public final class KeyboardMappingState {
    private KeyboardMappingState(){}
    public static void clear(KeyMapping mapping){
        var state=(KeyMappingStateAccess)(Object)mapping;
        state.piq$setDown(false);
        state.piq$setClicks(0);
    }
    public static void clearKeyboard(KeyMapping[] mappings){
        for(var mapping:mappings)if(mapping.getKey().getType()!=InputConstants.Type.MOUSE)clear(mapping);
    }
    /** The current lane owns only selected key mappings; unrelated keys and all mouse actions survive. */
    public static void clearCaptured(KeyMapping[] mappings,Predicate<KeyMapping> captured){
        for(var mapping:mappings)if(mapping.getKey().getType()!=InputConstants.Type.MOUSE&&captured.test(mapping))clear(mapping);
    }
    /** Explicit game bindings may include a mouse button; never clear unrelated mouse actions. */
    public static void clearSelected(KeyMapping[] mappings,Predicate<KeyMapping> captured){
        for(var mapping:mappings)if(captured.test(mapping))clear(mapping);
    }
}
