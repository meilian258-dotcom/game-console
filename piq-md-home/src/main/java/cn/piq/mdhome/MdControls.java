// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.HomeApplianceControl;
import cn.piq.fcarcade.home.ApplianceRay;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** From the supplied MD2 model: power slider x9.64..10.66,y2.303..2.474,z7.855..8.225. */
public final class MdControls {
    // Hit tolerance only, not a larger block selection/collision shape.
    public static final AABB POWER=new AABB(9.15/16,2.15/16,7.35/16,11.15/16,3.1/16,8.75/16);
    public static final AABB RESET=new AABB(4.9/16,2.15/16,7.35/16,6.85/16,3.1/16,8.75/16);
    public static final AABB P1=new AABB(8.9/16,0,0,13.2/16,1.35/16,3.9/16);
    public static final AABB P2=new AABB(2.8/16,0,0,7.1/16,1.35/16,3.9/16);
    public static final AABB AV=new AABB(5.9/16,.6/16,14.9/16,7.0/16,1.8/16,15.6/16);
    private static final ApplianceRay.Box[] BOXES={box(POWER,HomeApplianceControl.POWER),box(RESET,HomeApplianceControl.RESET),
            box(P1,HomeApplianceControl.CONTROLLER_ONE),box(P2,HomeApplianceControl.CONTROLLER_TWO),box(AV,HomeApplianceControl.VIDEO_DISCONNECT),
            ApplianceRay.units(HomeApplianceControl.NONE,3.4,.2,6.5,12.6,2.29,15.321)};
    private static ApplianceRay.Box box(AABB b,HomeApplianceControl action){return new ApplianceRay.Box(action,
            new ApplianceRay.Point(b.minX,b.minY,b.minZ),new ApplianceRay.Point(b.maxX,b.maxY,b.maxZ));}
    public static HomeApplianceControl pick(Vec3 eye,Vec3 end){
        return ApplianceRay.pick(new ApplianceRay.Point(eye.x,eye.y,eye.z),new ApplianceRay.Point(end.x,end.y,end.z),BOXES);
    }
    private MdControls(){}
}
