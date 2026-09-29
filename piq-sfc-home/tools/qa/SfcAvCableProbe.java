import cn.piq.sfchome.client.SfcAvCableGeometry;
import cn.piq.sfchome.client.SfcAvCableGeometry.*;
import cn.piq.fcarcade.client.HomeAvCableMesh;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout;
import cn.piq.fcarcade.home.*;
import java.util.*;

public final class SfcAvCableProbe {
    public static Endpoint television(int kind,int turns,double dx,double dy,double dz) {
        var sockets=HomeAvCableMesh.tvSockets(kind==1,kind>=2&&kind<5,kind==3,kind==4,kind==5,turns,new HomeHardwareRenderLayout.Point(dx,dy,dz));
        var p=new ArrayList<Vec>();for(var v:sockets)p.add(new Vec(v.x(),v.y(),v.z()));
        Box box;
        if(kind==2){var b=LcdTvLayout.bounds(turns);box=new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16);}
        else if(kind==3){var b=WideLcdTvLayout.bounds(turns);box=new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16);}
        else if(kind==4){var b=LargeLcdTvLayout.bounds(turns);box=new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16);}
        else if(kind==5){var b=VintageTvLayout.bounds(turns);box=new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16);}
        else{var b=HomeHardwareRenderLayout.tvBounds(turns,kind==1);box=new Box(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z());}
        return new Endpoint(p,new Box(dx+box.minX(),dy+box.minY(),dz+box.minZ(),dx+box.maxX(),dy+box.maxY(),dz+box.maxZ()),SfcAvCableGeometry.outward(turns),dy,1);
    }
    private static String point(Vec p){return "["+p.x()+","+p.y()+","+p.z()+"]";}
    private static String box(Box b){return "["+b.minX()+","+b.minY()+","+b.minZ()+","+b.maxX()+","+b.maxY()+","+b.maxZ()+"]";}
    private static void sample(int kind,int ct,int tt,double dx,double dy,double dz) {
        Endpoint c=SfcAvCableGeometry.console(ct),t=television(kind,tt,dx,dy,dz);Mesh m=SfcAvCableGeometry.build(c,t);var s=new StringBuilder();
        s.append("{\"visible\":").append(m.visible()).append(",\"rejection\":\"").append(m.rejection()).append("\",\"support\":").append(m.supportY());
        s.append(",\"console_box\":").append(box(c.housing())).append(",\"tv_box\":").append(box(t.housing()));
        s.append(",\"console_sockets\":[");for(int i=0;i<3;i++){if(i>0)s.append(',');s.append(point(c.sockets().get(i)));}s.append(']');
        s.append(",\"tv_sockets\":[");for(int i=0;i<3;i++){if(i>0)s.append(',');s.append(point(t.sockets().get(i)));}s.append(']');
        s.append(",\"trunk\":[");for(int i=0;i<m.trunk().size();i++){if(i>0)s.append(',');s.append(point(m.trunk().get(i)));}s.append(']');
        s.append(",\"quads\":[");for(int i=0;i<m.quads().size();i++){if(i>0)s.append(',');Quad q=m.quads().get(i);s.append("[\"").append(q.part()).append("\",").append(q.color()).append(',').append(point(q.a())).append(',').append(point(q.b())).append(',').append(point(q.c())).append(',').append(point(q.d())).append(',').append(point(q.normal())).append(']');}s.append("]}");
        System.out.println(s);
    }
    public static void main(String[] args) {
        if(args.length==6){sample(Integer.parseInt(args[0]),Integer.parseInt(args[1]),Integer.parseInt(args[2]),Double.parseDouble(args[3]),Double.parseDouble(args[4]),Double.parseDouble(args[5]));return;}
        if(args.length==1&&args[0].equals("--edge")){edgeMatrix();return;}
        int good=0,blocked=0,bad=0,max=0;Map<String,Integer> reasons=new TreeMap<>();
        for(int kind=0;kind<6;kind++)for(int ct=0;ct<4;ct++)for(int tt=0;tt<4;tt++)for(int axis=0;axis<4;axis++) {
            double dx=axis==0?-4:axis==1?4:0,dz=axis==2?-4:axis==3?4:0;
            Endpoint c=SfcAvCableGeometry.console(ct),t=television(kind,tt,dx,0,dz);Mesh m=SfcAvCableGeometry.build(c,t);
            if(!m.visible()){blocked++;reasons.merge(m.rejection(),1,Integer::sum);continue;}good++;max=Math.max(max,m.quads().size());
            for(Vec p:m.trunk())if(Math.abs(p.y()-SfcAvCableGeometry.TABLE_CLEARANCE)>1e-9)bad++;
            for(Quad q:m.quads())for(Vec p:List.of(q.a(),q.b(),q.c(),q.d()))if(!p.finite()||p.y()< -1e-8)bad++;
        }
        System.out.println("MATRIX good="+good+" blocked="+blocked+" bad="+bad+" max="+max+" reasons="+reasons);
        if(bad>0||blocked>0)System.exit(1);
    }
    private static void edgeMatrix() {
        int visible=0,blocked=0,bad=0,mixed=0;
        for(int kind=0;kind<6;kind++)for(int ct=0;ct<4;ct++)for(int tt=0;tt<4;tt++)for(int variant=0;variant<10;variant++) {
            double dx=variant<8?new double[]{0,.75,1,1.5,-.75,-1,-1.5,2}[variant]:-4;
            double dy=variant==8?1:variant==9?-1:0;
            Endpoint c=SfcAvCableGeometry.console(ct),t=television(kind,tt,dx,dy,0);Mesh m=SfcAvCableGeometry.build(c,t);
            if(!m.visible()){blocked++;if(m.rejection().isEmpty())bad++;continue;}visible++;if(dy!=0)mixed++;
            if(m.quads().size()>SfcAvCableGeometry.MAX_QUADS||m.trunk().size()<2)bad++;
            for(Vec p:m.trunk())if(Math.abs(p.y()-(Math.max(0,dy)+.022))>1e-9)bad++;
            for(Quad q:m.quads())for(Vec p:List.of(q.a(),q.b(),q.c(),q.d()))if(!p.finite()||p.y()<Math.min(0,dy)-1e-8)bad++;
        }
        System.out.println("EDGE total=960 visible="+visible+" blocked="+blocked+" mixed="+mixed+" bad="+bad);
        if(bad>0||visible==0||blocked==0||mixed==0)System.exit(1);
    }
}
