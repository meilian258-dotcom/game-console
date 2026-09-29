package cn.piq.fcarcade.client.cabinet;

import net.minecraft.client.Minecraft;

/** Prevent a held use key from reopening a cabinet immediately after its right-click exit. */
public final class CabinetUseGuard {
    private static final CabinetUseLatch LATCH=new CabinetUseLatch();
    private CabinetUseGuard(){}
    public static void suppressWhileHeld(){LATCH.exited(Minecraft.getInstance().options.keyUse.isDown());}
    public static void suppressPowerRepeats(){LATCH.powerPress(Minecraft.getInstance().options.keyUse.isDown());}
    public static boolean inputBlocked(){observe();return LATCH.blocksInput();}
    public static boolean blocked(){
        observe();return LATCH.blocksReply();
    }
    private static void observe(){
        var mc=Minecraft.getInstance();
        LATCH.observe(mc.player!=null&&mc.level!=null&&mc.options.keyUse.isDown());
    }
}
