package cn.piq.fcarcade.home;

import java.util.List;

/** One-cell cartridge-writing desktop. All bounds use model units (16 = one block). */
public final class CartridgeComputerLayout {
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private static final List<Bounds> NORTH=List.of(
            new Bounds(1,0,5,15,3.35,14.6),
            new Bounds(1.8,3.35,5.25,13.2,13.5,14.4),
            new Bounds(3.2,.25,.6,14.7,1.55,4.6),
            new Bounds(.65,.25,1.2,2.65,1.25,4.1),
            new Bounds(9.1,.3,4.6,9.3,.55,5),
            new Bounds(1.5,.3,4.1,1.7,.55,5));
    private static final List<List<Bounds>> PARTS=List.of(rotate(0),rotate(1),rotate(2),rotate(3));
    private static final List<Bounds> BOUNDS=PARTS.stream().map(CartridgeComputerLayout::enclose).toList();
    private CartridgeComputerLayout() {}
    /** North=0, east=1, south=2, west=3, matching the baked blockstate rotation. */
    public static List<Bounds> parts(int clockwiseQuarterTurns) {
        return PARTS.get(Math.floorMod(clockwiseQuarterTurns,4));
    }
    public static Bounds bounds(int clockwiseQuarterTurns) {
        return BOUNDS.get(Math.floorMod(clockwiseQuarterTurns,4));
    }
    private static List<Bounds> rotate(int turns) {
        return NORTH.stream().map(b->{
            double ax=b.minX(),az=b.minZ(),bx=b.maxX(),bz=b.maxZ();
            for(int i=0;i<turns;i++) {double ax0=ax,bx0=bx;ax=16-az;az=ax0;bx=16-bz;bz=bx0;}
            return new Bounds(Math.min(ax,bx),b.minY(),Math.min(az,bz),Math.max(ax,bx),b.maxY(),Math.max(az,bz));
        }).toList();
    }
    private static Bounds enclose(List<Bounds> p) {
        return new Bounds(p.stream().mapToDouble(Bounds::minX).min().orElseThrow(),
                p.stream().mapToDouble(Bounds::minY).min().orElseThrow(),
                p.stream().mapToDouble(Bounds::minZ).min().orElseThrow(),
                p.stream().mapToDouble(Bounds::maxX).max().orElseThrow(),
                p.stream().mapToDouble(Bounds::maxY).max().orElseThrow(),
                p.stream().mapToDouble(Bounds::maxZ).max().orElseThrow());
    }
}
