package cn.piq.fcarcade.home;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import java.util.List;
import java.util.function.Predicate;

/** Two rows, anchored at the first column. Exactly four or six occupied cells. */
public final class PanelTvFootprint {
    public enum Facing {NORTH,EAST,SOUTH,WEST}
    public record Cell(int part,int x,int y,int z) {}
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private PanelTvFootprint() {}
    public static int cellCount(boolean wide){return wide?6:4;}
    public static Cell cell(Facing facing,int part,boolean wide){
        if(part<0||part>=cellCount(wide))throw new IllegalArgumentException("Panel cell index");
        int width=wide?3:2,x=part%width,y=part/width;
        return switch(facing){case NORTH->new Cell(part,x,y,0);case EAST->new Cell(part,0,y,x);case SOUTH->new Cell(part,-x,y,0);case WEST->new Cell(part,0,y,-x);};
    }
    public static List<Cell> cells(Facing facing,boolean wide){return java.util.stream.IntStream.range(0,cellCount(wide)).mapToObj(i->cell(facing,i,wide)).toList();}
    public static boolean canPlace(Facing facing,boolean wide,Predicate<Cell> available){return cells(facing,wide).stream().allMatch(available);}
    public static Bounds selection(Facing facing,int part,boolean wide){return selection(facing,part,wide,false);}
    public static Bounds selection(Facing facing,int part,boolean wide,boolean wall){
        var style=wide?(wall?ArcadeDisplayStyle.HOME_PANEL_3_WALL:ArcadeDisplayStyle.HOME_PANEL_3):(wall?ArcadeDisplayStyle.HOME_PANEL_2_WALL:ArcadeDisplayStyle.HOME_PANEL_2);
        var b=UserTvLayout.bounds(style,facing.ordinal());var c=cell(facing,part,wide);
        return new Bounds(b.minX()-c.x()*16,b.minY()-c.y()*16,b.minZ()-c.z()*16,b.maxX()-c.x()*16,b.maxY()-c.y()*16,b.maxZ()-c.z()*16);
    }
    public static Bounds clipped(Facing facing,int part,boolean wide){return clipped(facing,part,wide,false);}
    public static Bounds clipped(Facing facing,int part,boolean wide,boolean wall){
        var b=selection(facing,part,wide,wall);
        return new Bounds(Math.max(0,b.minX()),Math.max(0,b.minY()),Math.max(0,b.minZ()),Math.min(16,b.maxX()),Math.min(16,b.maxY()),Math.min(16,b.maxZ()));
    }
}
