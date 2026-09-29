import cn.piq.fcarcade.client.HomeAvCableLayout;
import cn.piq.fcarcade.client.HomeAvCableMesh;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Independent offline UX probe; runs the real packaged pure geometry, not a reimplementation. */
public final class AvCableAlpha7Probe {
    private static final double EPS = 1e-8;
    private static final int[][] OFFSETS = {{4,0},{-4,0},{0,4},{0,-4},{4,4},{-4,-4}};
    private static final List<String> failures = new ArrayList<>();
    private static void require(boolean condition, String message) { if (!condition) failures.add(message); }
    private static double distance(Point a, Point b) { return Math.hypot(a.x()-b.x(), a.z()-b.z()); }
    private static String key(int console, int tv, int a, int b, double x, double y, double z) {
        return String.format(Locale.ROOT,"c%d-t%d-r%d%d-%.1f,%.1f,%.1f",console,tv,a,b,x,y,z);
    }
    private static long routeHash(List<Point> route) {
        long hash = 0xcbf29ce484222325L;
        for (Point p : route) for (double value : new double[]{p.x(),p.y(),p.z()}) {
            // Quantization removes irrelevant trig/JVM display rounding while detecting geometric drift.
            hash ^= Math.round(value*1e9); hash *= 0x100000001b3L;
        }
        return hash;
    }
    public static void main(String[] args) {
        boolean referenceOnly = args.length > 0 && args[0].equals("--unequal-only");
        int checked = 0, vertices = 0, minRoute = Integer.MAX_VALUE, maxRoute = 0, maxQuads = 0;
        double minBottom = Double.POSITIVE_INFINITY, minContact = 1, maxContact = 0;
        if (!referenceOnly) {
            for (int console=0;console<3;console++) for(int tv=0;tv<3;tv++)
                for(int a=0;a<4;a++) for(int b=0;b<4;b++) for(int[] offset:OFFSETS) {
                    double x=offset[0],z=offset[1]; String key=key(console,tv,a,b,x,0,z);
                    var route=HomeAvCableLayout.route(console!=0,console==2,a,tv==1,tv==2,b,x,0,z);
                    var mesh=HomeAvCableMesh.build(console!=0,console==2,a,tv==1,tv==2,b,x,0,z);
                    require(!route.isEmpty()&&!mesh.isEmpty(),"Separated devices must keep a visible cable: "+key);
                    if(route.isEmpty()||mesh.isEmpty())continue;
                    checked++; minRoute=Math.min(minRoute,route.size()); maxRoute=Math.max(maxRoute,route.size()); maxQuads=Math.max(maxQuads,mesh.size());
                    require(route.size()<=256&&mesh.size()<=6000,"Bounded route/mesh budget: "+key);
                    double total=0, contact=0;
                    for(int i=2;i<route.size()-1;i++) {
                        double length=distance(route.get(i-1),route.get(i)); total+=length;
                        if(Math.abs(route.get(i-1).y()-.027)<EPS&&Math.abs(route.get(i).y()-.027)<EPS)contact+=length;
                    }
                    double fraction=contact/total; minContact=Math.min(minContact,fraction);maxContact=Math.max(maxContact,fraction);
                    require(fraction>=.50,"At least half the long trunk rests on a common table: "+key+" fraction="+fraction);
                    require(route.get(1).y()<=route.getFirst().y()+EPS&&route.get(route.size()-2).y()<=route.getLast().y()+EPS,
                            "Flexible fan-outs must droop, not rise above either port: "+key);
                    long yellow=0,white=0,red=0;
                    for(var q:mesh) {
                        if(q.color()==HomeAvCableMesh.YELLOW)yellow++;
                        if(q.color()==HomeAvCableMesh.WHITE)white++;
                        if(q.color()==HomeAvCableMesh.RED)red++;
                        for(Point p:List.of(q.a(),q.b(),q.c(),q.d())) {
                            vertices++;minBottom=Math.min(minBottom,p.y());
                            require(Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z()),"Finite actual surface: "+key);
                            require(p.y()>=-EPS,"Actual pipe/plug below common table: "+key+" y="+p.y());
                        }
                    }
                    require(yellow==96&&white==96&&red==96,"Exactly three colored plugs per end: "+key);
                }
            require(checked==864,"Expected all 864 separated same-level combinations, actual="+checked);
        }
        StringBuilder unequal=new StringBuilder();int unequalCount=0;
        for(int console=0;console<2;console++) for(int tv=0;tv<3;tv++)
            for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int dy:new int[]{-2,2}) {
                var route=HomeAvCableLayout.route(console!=0,false,a,tv==1,tv==2,b,4,dy,0);
                if(unequalCount++>0)unequal.append(',');
                unequal.append('"').append(key(console,tv,a,b,4,dy,0)).append("\":\"").append(Long.toUnsignedString(routeHash(route),16)).append('"');
            }
        System.out.printf(Locale.ROOT,"{\"ok\":%s,\"same_level_cases\":%d,\"surface_vertices\":%d,\"minimum_actual_y\":%.12f,\"minimum_table_contact_fraction\":%.12f,\"maximum_table_contact_fraction\":%.12f,\"min_route_points\":%d,\"max_route_points\":%d,\"max_quads\":%d,\"unequal_route_hashes\":{%s},\"failures\":[",
                failures.isEmpty(),checked,vertices,Double.isFinite(minBottom)?minBottom:0,minContact,maxContact,checked>0?minRoute:0,maxRoute,maxQuads,unequal);
        for(int i=0;i<failures.size();i++){if(i>0)System.out.print(',');System.out.print('"'+failures.get(i).replace("\\","\\\\").replace("\"","\\\"")+'"');}
        System.out.println("]}");
        if(!failures.isEmpty())System.exit(1);
    }
}
