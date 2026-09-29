package cn.piq.fcarcade.home;

import java.util.List;
import java.util.function.Predicate;

/** Six reserved cells: center anchor, two half-width sides, and three short top proxies. */
public final class LargeLcdTvFootprint {
    public enum Facing { NORTH,EAST,SOUTH,WEST }
    public record Cell(int part,int x,int y,int z) {}
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private LargeLcdTvFootprint() {}
    public static int cellCount(boolean ignored){return 6;}
    public static Cell cell(Facing facing,int part,boolean ignored) {
        if(part<0||part>=6)throw new IllegalArgumentException("Large LCD part outside six-cell layout");
        int x=switch(part%3){case 1->-1;case 2->1;default->0;};int y=part/3;
        return switch(facing){
            case NORTH->new Cell(part,x,y,0);case EAST->new Cell(part,0,y,x);
            case SOUTH->new Cell(part,-x,y,0);case WEST->new Cell(part,0,y,-x);
        };
    }
    public static List<Cell> cells(Facing facing,boolean ignored){return java.util.stream.IntStream.range(0,6).mapToObj(p->cell(facing,p,false)).toList();}
    public static boolean canPlace(Facing facing,boolean ignored,Predicate<Cell> available){return cells(facing,false).stream().allMatch(available);}
    public static Bounds selection(Facing facing,int part,boolean ignored) {
        var c=cell(facing,part,false);var b=LargeLcdTvLayout.bounds(facing.ordinal());
        return new Bounds(b.minX()-c.x()*16,-c.y()*16,b.minZ()-c.z()*16,
                b.maxX()-c.x()*16,b.maxY()-c.y()*16,b.maxZ()-c.z()*16);
    }
    public static Bounds clipped(Facing facing,int part,boolean ignored) {
        var b=selection(facing,part,false);
        return new Bounds(Math.max(0,b.minX()),Math.max(0,b.minY()),Math.max(0,b.minZ()),
                Math.min(16,b.maxX()),Math.min(16,b.maxY()),Math.min(16,b.maxZ()));
    }
}
