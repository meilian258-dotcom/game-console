package cn.piq.mdhome;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Conservative separate case/pad/card bounds in the supplied model, never a tall full-block box. */
public final class MdOutline {
    private static final VoxelShape[][] SHAPES=new VoxelShape[2][4];
    static {
        for(int card=0;card<2;card++)for(int turn=0;turn<4;turn++){
            var shape=Shapes.empty();
            var parts=new java.util.ArrayList<AABB>();
            parts.add(new AABB(3.4/16,.2/16,6.45/16,12.6/16,2.48/16,15.321/16));
            // Keep a small empty dock target after taking the pad so it remains easy to return.
            parts.add(MdControls.P1);parts.add(MdControls.P2);
            if(card!=0)parts.add(new AABB(5.6/16,2.29/16,11.7/16,10.4/16,4.72/16,12.7/16));
            for(var part:parts)shape=Shapes.or(shape,Shapes.create(rotate(part,turn)));
            SHAPES[card][turn]=shape.optimize();
        }
    }
    static AABB rotate(AABB box,int turns){
        for(int i=0;i<Math.floorMod(turns,4);i++)box=new AABB(1-box.maxZ,box.minY,box.minX,1-box.minZ,box.maxY,box.maxX);
        return box;
    }
    public static VoxelShape shape(boolean card,int turns){return SHAPES[card?1:0][Math.floorMod(turns,4)];}
    private MdOutline(){}
}
