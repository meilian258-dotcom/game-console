import cn.piq.fcarcade.client.HomeAvCableLayout;
import cn.piq.fcarcade.client.HomeAvCableMesh;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;

/** Read-only: identical probe runs against alpha6 JAR classes and current compiled classes. */
public class AvTableMeshProbe {
    private static String point(Point p) {
        return "["+p.x()+","+p.y()+","+p.z()+"]";
    }
    public static void main(String[] args) {
        int console=Integer.parseInt(args[0]), tv=Integer.parseInt(args[1]);
        int a=Integer.parseInt(args[2]), b=Integer.parseInt(args[3]);
        double x=Double.parseDouble(args[4]), y=Double.parseDouble(args[5]), z=Double.parseDouble(args[6]);
        boolean subor=console!=0,wide=console==2,centered=tv==1,lcd=tv==2;
        var route=HomeAvCableLayout.route(subor,wide,a,centered,lcd,b,x,y,z);
        var mesh=HomeAvCableMesh.build(subor,wide,a,centered,lcd,b,x,y,z);
        StringBuilder json=new StringBuilder("{\"route\":[");
        for(int i=0;i<route.size();i++){if(i>0)json.append(',');json.append(point(route.get(i)));}
        json.append("],\"mesh\":[");
        for(int i=0;i<mesh.size();i++){
            if(i>0)json.append(',');var q=mesh.get(i);
            json.append('[').append(q.color()).append(',').append(point(q.a())).append(',')
                .append(point(q.b())).append(',').append(point(q.c())).append(',').append(point(q.d())).append(']');
        }
        System.out.print(json.append("]}"));
    }
}
