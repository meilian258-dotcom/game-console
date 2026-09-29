package cn.piq.computer;
import net.minecraft.world.phys.Vec3;
/** One transform for parts, collision, buttons, lamps and connectors. */
public final class ComputerGeometry {
    public static final double SCALE=.6;
    public static Vec3 scaled(Vec3 p){return new Vec3(.5+(p.x-.5)*SCALE,p.y*SCALE,.5+(p.z-.5)*SCALE);}
    public static Vec3 unscaled(Vec3 p){return new Vec3(.5+(p.x-.5)/SCALE,p.y/SCALE,.5+(p.z-.5)/SCALE);}
    public static Vec3 units(double x,double y,double z){return scaled(new Vec3(x/16,y/16,z/16));}
    public static final Vec3 HDMI=units(11.1,7.105,15.35);
    private ComputerGeometry(){}
}
