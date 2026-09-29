import cn.piq.fcarcade.world.DualCabinetFootprint;
import cn.piq.fcarcade.world.DualCabinetFootprint.*;
import java.nio.file.Path;
import java.util.HashSet;

/** Loads the supplied final jar only. No Minecraft, world or save manipulation. */
public final class UserModel22FootprintProbe {
    private static int assertions;
    private static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("assertion "+assertions);}
    private static double volume(Bounds b){return Math.max(0,b.maxX()-b.minX())*Math.max(0,b.maxY()-b.minY())*Math.max(0,b.maxZ()-b.minZ());}
    public static void main(String[]args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();
        for(Class<?> c:new Class<?>[]{DualCabinetFootprint.class,Cell.class,Bounds.class,Facing.class})check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected));
        check(DualCabinetFootprint.CELL_COUNT==12);
        for(Facing f:Facing.values()){
            var cells=DualCabinetFootprint.cells(f);check(cells.size()==12);var positions=new HashSet<String>();double total=0;int nonempty=0;
            for(int part=0;part<12;part++){
                Cell c=cells.get(part);int x=part%2,y=part/2%3,z=part/6;
                int ex=switch(f){case NORTH->x;case EAST->-z;case SOUTH->-x;case WEST->z;};
                int ez=switch(f){case NORTH->z;case EAST->x;case SOUTH->-z;case WEST->-x;};
                check(c.part()==part&&c.x()==ex&&c.y()==y&&c.z()==ez);check(positions.add(c.x()+","+c.y()+","+c.z()));
                Bounds clipped=DualCabinetFootprint.clipped(f,part);double v=volume(clipped);total+=v;if(v>0)nonempty++;
                check(clipped.minX()>=0&&clipped.minY()>=0&&clipped.minZ()>=0&&clipped.maxX()<=16&&clipped.maxY()<=16&&clipped.maxZ()<=16);
                check((v==0)==(y==2));final int blocked=part;check(!DualCabinetFootprint.canPlace(f,test->test.part()!=blocked));
            }
            check(nonempty==8);check(Math.abs(total-24*32*17.6)<1e-7);check(Math.abs(total-volume(DualCabinetFootprint.bounds(f)))<1e-7);
            check(DualCabinetFootprint.canPlace(f,test->true));
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"final-jar-only\",\"legacy_cells_preserved\":12,\"nonempty_collision_cells\":8,\"empty_top_proxy_cells\":4,\"minecraft_or_native_core_started\":false}");
    }
}
