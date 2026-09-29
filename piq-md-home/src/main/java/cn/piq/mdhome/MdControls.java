// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.HomeApplianceControl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** From the supplied MD2 model: power slider x9.64..10.66,y2.303..2.474,z7.855..8.225. */
public final class MdControls {
    // Hit tolerance only, not a larger block selection/collision shape.
    public static final AABB POWER=new AABB(9.15/16,2.15/16,7.35/16,11.15/16,3.1/16,8.75/16);
    public static HomeApplianceControl pick(Vec3 eye,Vec3 end){return POWER.clip(eye,end).isPresent()?HomeApplianceControl.POWER:HomeApplianceControl.NONE;}
    private MdControls(){}
}
