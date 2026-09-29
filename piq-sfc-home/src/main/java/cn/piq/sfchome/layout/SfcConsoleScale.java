package cn.piq.sfchome.layout;

/** Visual-only scale of user-supplied console geometry. Coordinates here are 1/16-block model units. */
public final class SfcConsoleScale {
    public static final double SCALE=1.5, PIVOT_X=8, PIVOT_Y=0, PIVOT_Z=10.66025, DOCK_Z=-1.25;
    public record Point(double x,double y,double z) {}
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private SfcConsoleScale() {}
    public static Point console(double x,double y,double z){return new Point(PIVOT_X+(x-PIVOT_X)*SCALE,y*SCALE,PIVOT_Z+(z-PIVOT_Z)*SCALE);}
    public static Point part(String group,String part,double x,double y,double z){
        if(group.equals("body")||group.equals("slot_cover")||group.equals("inserted"))return console(x,y,z);
        if(!group.equals("p1_docked")&&!group.equals("p2_docked"))return new Point(x,y,z);
        if(part.equals("console_ports"))return console(x,y,z);
        if(part.equals("p1_cable")||part.equals("p2_cable")){
            // Rigid at both strain-relief ends; interpolate only the existing free cord.
            // Unlike scaling the cable, translating its end sections keeps their old thickness.
            double t=Math.max(0,Math.min(1,(z-3.17)/(5.16-3.17)));
            double anchorX=group.equals("p1_docked")?10.025:5.975;
            Point end=console(anchorX,.7225,5.275);
            return new Point(x+(end.x-anchorX)*t,y+(end.y-.7225)*t,
                    z+DOCK_Z*(1-t)+(end.z-5.275)*t);
        }
        return new Point(x,y,z+DOCK_Z);
    }
    /** Input is the already bounds-validated raw mesh. Never changes the parser or original arrays. */
    public static float[] vertices(String group,String part,float[] input){
        if(group.equals("controller")||group.equals("cartridge"))return input;
        if(input.length%24!=0)throw new IllegalArgumentException("SFC triangle stride");
        float[] out=input.clone();
        for(int i=0;i<out.length;i+=8){Point p=part(group,part,input[i]*16,input[i+1]*16,input[i+2]*16);out[i]=(float)(p.x/16);out[i+1]=(float)(p.y/16);out[i+2]=(float)(p.z/16);}
        if(part.equals("p1_cable")||part.equals("p2_cable"))for(int i=0;i<out.length;i+=24){
            double ax=out[i+8]-out[i],ay=out[i+9]-out[i+1],az=out[i+10]-out[i+2];
            double bx=out[i+16]-out[i],by=out[i+17]-out[i+1],bz=out[i+18]-out[i+2];
            double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,n=Math.sqrt(nx*nx+ny*ny+nz*nz);
            if(n<=1e-12)throw new IllegalArgumentException("SFC cord collapsed");
            for(int v=0;v<3;v++){out[i+v*8+5]=(float)(nx/n);out[i+v*8+6]=(float)(ny/n);out[i+v*8+7]=(float)(nz/n);}
        }
        return out;
    }
    public static Bounds body(int turns){return rotate(new Bounds(2,0,3.344375,14,4.4475,17.976125),turns);}
    public static Bounds pad(int port,int turns){double x=port==0?8.62:1.62;return rotate(new Bounds(x,0,.4625+DOCK_Z,x+5.76,.764,3.083461+DOCK_Z),turns);}
    public static Bounds inserted(int turns){return rotate(new Bounds(4.16,3.27,11.634125,11.84,7.62,12.838625),turns);}
    public static Bounds render(int turns){return rotate(new Bounds(1.62,0,.4625+DOCK_Z,14.38,7.65,17.976125),turns);}
    public static Point rotate(double x,double y,double z,int turns){return switch(Math.floorMod(turns,4)){case 1->new Point(16-z,y,x);case 2->new Point(16-x,y,16-z);case 3->new Point(z,y,16-x);default->new Point(x,y,z);};}
    private static Bounds rotate(Bounds b,int turns){Point a=rotate(b.minX,b.minY,b.minZ,turns),z=rotate(b.maxX,b.maxY,b.maxZ,turns);return new Bounds(Math.min(a.x,z.x),b.minY,Math.min(a.z,z.z),Math.max(a.x,z.x),b.maxY,Math.max(a.z,z.z));}
}
