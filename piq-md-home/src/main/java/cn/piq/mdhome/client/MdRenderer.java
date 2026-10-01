// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import cn.piq.mdhome.*;
import cn.piq.fcarcade.client.*;
import cn.piq.fcarcade.layout.ControllerCableGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;

/** Only MD geometry/identities; all cable tessellation, poses and lamp materials are shared. */
public final class MdRenderer implements BlockEntityRenderer<MdConsole> {
    private final ExternalHomeAvRenderer<MdConsole> av=new ExternalHomeAvRenderer<>(
            new HomeHardwareRenderLayout.Point(6.45/16,1.23/16,15.321/16),
            new UserTvCableMesh.Bounds(3.4/16,0,6.5/16,12.6/16,6.5/16,15.321/16),.30);
    public void render(MdConsole c,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        av.render(c,partial,poses,buffers,light,overlay);
        int turn=switch(c.getBlockState().getValue(MdBlock.FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};
        poses.pushPose();try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90f*turn));poses.translate(-.5,0,-.5);
            // Added top lens immediately beside the physical power slider; not a world light source.
            PowerIndicatorRenderer.face(poses,buffers,light,overlay,c.visualPowered()||c.running(),10.85/16,2.31/16,8.50/16,11.12/16,2.31/16,8.77/16);
            ContentCardCoverRenderer.north(cn.piq.fcarcade.home.content.ContentCardData.cover(c.cartridge()),poses,buffers,light,overlay,
                    6.045/16,2.5/16,9.955/16,4.22/16,11.782/16);
        }finally{poses.popPose();}
        ControllerCableRenderer.render(c,turn,ControllerCableGeometry.Style.SUBOR,
                new ControllerCableRenderer.Lease(c.borrower(),c.loan()),null,
                (stack,port,lease)->port==0&&lease.equals(MdController.loan(stack)),
                new Point(9.85/16,.88/16,6.45/16),null,partial,poses,buffers,light,overlay);
    }
    public boolean shouldRenderOffScreen(MdConsole c){return true;}
    public AABB getRenderBoundingBox(MdConsole c){return av.getRenderBoundingBox(c).minmax(ControllerCableRenderer.bounds(c));}
}
