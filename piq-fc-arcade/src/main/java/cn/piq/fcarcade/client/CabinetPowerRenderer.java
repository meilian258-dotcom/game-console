package cn.piq.fcarcade.client;

import cn.piq.fcarcade.layout.*;
import cn.piq.fcarcade.world.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.*;

final class CabinetPowerRenderer {
    private CabinetPowerRenderer(){}
    static void render(LegacyFcArcadeBlockEntity machine,PoseStack poses,MultiBufferSource buffers){
        if(!cn.piq.fcarcade.cabinet.CabinetCoinPolicy.supported(machine.cabinetBackend().toString()))return;
        var facing=machine.getBlockState().getValue(FcArcadeBlock.FACING);
        boolean dual=machine instanceof DualCabinetBlockEntity;
        boolean compact=dual&&((DualCabinetBlockEntity)machine).compactFootprint();
        poses.pushPose();
        try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90*RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ())));poses.translate(-.5,0,-.5);
            var vertices=buffers.getBuffer(CabinetPowerMaterial.SOLID);
            var boxes=machine instanceof PortraitCabinetBlockEntity?PortraitCabinetGeometry.powerBoxes():CabinetPowerGeometry.boxes(dual,compact);
            for(var b:boxes){
                for(var face:CabinetPowerMesh.faces(machine.visualPowered())){
                    int rgb=face.rgb();
                    for(var v:face.vertices()){
                        var p=CabinetPowerGeometry.meshPoint(b,v.u(),v.v(),v.w());
                        vertices.addVertex(poses.last().pose(),(float)p.x(),(float)p.y(),(float)p.z())
                            .setColor(rgb>>16&255,rgb>>8&255,rgb&255,255);
                    }
                }
            }
        }finally{poses.popPose();}
    }
}
