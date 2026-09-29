package cn.piq.fcarcade.layout;

import java.util.ArrayList;
import java.util.List;

/** Small solid rocker; local axes are width, up and outward from the side panel.
 * No textures or game state: these same vertices can be inspected by headless tests. */
public final class CabinetPowerMesh {
    public static final double SCALE=.5, HALF_WIDTH=.080*SCALE, HALF_HEIGHT=.110*SCALE;
    public record Vertex(double u,double v,double w) {}
    public record Quad(Vertex a,Vertex b,Vertex c,Vertex d,int rgb) {
        public List<Vertex> vertices(){return List.of(a,b,c,d);}
    }
    private static final List<Quad> OFF=build(false),ON=build(true);
    private CabinetPowerMesh(){}
    public static List<Quad> faces(boolean powered){return powered?ON:OFF;}
    public static double surface(double v,boolean powered){return .022+(powered?-1:1)*v*.23;}
    private static List<Quad> build(boolean on){
        var q=new ArrayList<Quad>();
        // Four separate black bezel rails leave a recessed well around the red paddle.
        box(q,-.080,-.110,0,-.060,.110,.009,0x222326);
        box(q,.060,-.110,0,.080,.110,.009,0x222326);
        box(q,-.060,-.110,0,.060,-.089,.009,0x28292C);
        box(q,-.060,.089,0,.060,.110,.009,0x303135);
        box(q,-.060,-.089,0,.060,.089,.002,0x09090B);
        int red=on?0xFF3023:0x761A17;
        // Bevel around a tilted inset face; ON depresses the upper I end.
        double[][] outline={{-.046,-.080},{.046,-.080},{.056,-.070},{.056,.070},
                {.046,.080},{-.046,.080},{-.056,.070},{-.056,-.070}};
        for(int i=0;i<outline.length;i++){
            var p=outline[i];var n=outline[(i+1)%outline.length];
            var a=top(p[0],p[1],on,0);var b=top(n[0],n[1],on,0);
            var c=top(n[0]*.87,n[1]*.91,on,.004);var d=top(p[0]*.87,p[1]*.91,on,.004);
            q.add(new Quad(a,b,c,d,shade(red,i==4?1.13:.75)));
            q.add(new Quad(new Vertex(p[0],p[1],.002),new Vertex(n[0],n[1],.002),b,a,shade(red,.46)));
            // Degenerate fourth vertex is intentional: a triangle in a QUADS buffer.
            var center=top(0,0,on,.004);
            q.add(new Quad(center,d,c,center,red));
        }
        // Printed I and O, not a third detent. Slight depth offset prevents z fighting.
        int ink=on?0xFFF3DA:0xC9B8AF;
        rect(q,-.003,.027,.003,.053,on,ink);
        for(int i=0;i<16;i++){
            double a=i*Math.PI/8,b=(i+1)*Math.PI/8;
            q.add(new Quad(top(Math.cos(a)*.014,-.039+Math.sin(a)*.018,on,.005),
                    top(Math.cos(b)*.014,-.039+Math.sin(b)*.018,on,.005),
                    top(Math.cos(b)*.010,-.039+Math.sin(b)*.013,on,.005),
                    top(Math.cos(a)*.010,-.039+Math.sin(a)*.013,on,.005),ink));
        }
        return q.stream().map(f->new Quad(scaled(f.a()),scaled(f.b()),scaled(f.c()),scaled(f.d()),f.rgb())).toList();
    }
    private static Vertex scaled(Vertex p){return new Vertex(p.u()*SCALE,p.v()*SCALE,p.w()*SCALE);}
    private static Vertex top(double u,double v,boolean on,double extra){return new Vertex(u,v,surface(v,on)+extra);}
    private static void rect(List<Quad> q,double u1,double v1,double u2,double v2,boolean on,int rgb){
        q.add(new Quad(top(u1,v1,on,.005),top(u2,v1,on,.005),top(u2,v2,on,.005),top(u1,v2,on,.005),rgb));
    }
    private static int shade(int rgb,double factor){return (Math.min(255,(int)((rgb>>16&255)*factor))<<16)
            |(Math.min(255,(int)((rgb>>8&255)*factor))<<8)|Math.min(255,(int)((rgb&255)*factor));}
    private static void box(List<Quad> q,double u,double v,double w,double x,double y,double z,int rgb){
        var a=new Vertex(u,v,w);var b=new Vertex(x,v,w);var c=new Vertex(x,y,w);var d=new Vertex(u,y,w);
        var e=new Vertex(u,v,z);var f=new Vertex(x,v,z);var g=new Vertex(x,y,z);var h=new Vertex(u,y,z);
        q.add(new Quad(e,f,g,h,rgb));q.add(new Quad(a,e,h,d,shade(rgb,.7)));
        q.add(new Quad(f,b,c,g,shade(rgb,.8)));q.add(new Quad(h,g,c,d,shade(rgb,1.15)));
        q.add(new Quad(a,b,f,e,shade(rgb,.6)));
    }
}
