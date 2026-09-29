package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.util.List;

/** Core-neutral, complete-source video fitted in the two existing tilted cabinet glasses. */
public final class CabinetVideoGeometry {
    // Single / legacy dual / compact dual, bounded to 48 immutable frames.
    private static final Frame[][][] CACHE=new Frame[3][4][4];
    private CabinetVideoGeometry() {}

    public record Vertex(Point point,float u,float v) {}
    public record Uv(float u,float v) {}
    public record Frame(ScreenQuad glass,ScreenQuad image,List<Vertex> vertices,
                        double rawAspect,double displayAspect,int rotation) {
        public Frame { vertices=List.copyOf(vertices); }
    }

    /** rawAspect is the core's unrotated display aspect, not necessarily its pixel width/height. */
    public static double displayAspect(double rawAspect,int rotation) {
        if(!Double.isFinite(rawAspect)||rawAspect<=0||rawAspect>32)
            throw new IllegalArgumentException("Invalid cabinet source aspect");
        double result=(Math.floorMod(rotation,4)&1)==0?rawAspect:1/rawAspect;
        if(!Double.isFinite(result)||result<=0)throw new IllegalArgumentException("Invalid rotated cabinet aspect");
        return result;
    }

    /** libretro reports counter-clockwise content rotation: displayed top-left UV -> original texture UV. */
    public static Uv textureUv(float u,float v,int rotation) {
        if(!Float.isFinite(u)||!Float.isFinite(v)||u<0||u>1||v<0||v>1)
            throw new IllegalArgumentException("UV outside complete source");
        return switch(Math.floorMod(rotation,4)) {
            case 1 -> new Uv(1-v,u);
            case 2 -> new Uv(1-u,1-v);
            case 3 -> new Uv(v,1-u);
            default -> new Uv(u,v);
        };
    }

    public static synchronized Frame frame(boolean dual,int clockwiseFacingTurns,double rawAspect,int rotation) {
        return frame(dual,clockwiseFacingTurns,rawAspect,rotation,false);
    }
    public static synchronized Frame frame(boolean dual,int clockwiseFacingTurns,double rawAspect,int rotation,boolean compactDual) {
        int facing=Math.floorMod(clockwiseFacingTurns,4),turn=Math.floorMod(rotation,4),kind=dual?(compactDual?2:1):0;
        double aspect=displayAspect(rawAspect,turn);
        Frame cached=CACHE[kind][facing][turn];
        if(cached!=null&&Double.doubleToLongBits(cached.rawAspect())==Double.doubleToLongBits(rawAspect))return cached;
        ScreenQuad glass=dual?DualCabinetGeometry.screen(facing,compactDual):RocketArcadeGeometry.screen(facing);
        ScreenQuad image=ScreenAspectFit.fit(glass,aspect);
        if(image.width()<=0||image.height()<=0)throw new IllegalArgumentException("Cabinet aspect collapses the visible frame");
        // At the north-facing front, world max X is the viewer's left. Preserve the existing NES convention.
        var vertices=List.of(vertex(image.lowerMaxX(),0,1,turn),vertex(image.lowerMinX(),1,1,turn),
                vertex(image.upperMinX(),1,0,turn),vertex(image.upperMaxX(),0,0,turn));
        var result=new Frame(glass,image,vertices,rawAspect,aspect,turn);
        CACHE[kind][facing][turn]=result;
        return result;
    }
    /** Same rotation/letterbox convention on a caller-supplied physical glass. */
    public static Frame frame(ScreenQuad glass,double rawAspect,int rotation){
        int turn=Math.floorMod(rotation,4);double aspect=displayAspect(rawAspect,turn);
        var image=ScreenAspectFit.fit(glass,aspect);
        return new Frame(glass,image,List.of(vertex(image.lowerMaxX(),0,1,turn),vertex(image.lowerMinX(),1,1,turn),
                vertex(image.upperMinX(),1,0,turn),vertex(image.upperMaxX(),0,0,turn)),rawAspect,aspect,turn);
    }
    private static Vertex vertex(Point point,float u,float v,int rotation) {
        var uv=textureUv(u,v,rotation);return new Vertex(point,uv.u(),uv.v());
    }
}
