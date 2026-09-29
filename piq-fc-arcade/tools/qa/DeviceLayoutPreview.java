import cn.piq.fcarcade.client.ui.DeviceLayout;
public final class DeviceLayoutPreview {
    private static String rect(DeviceLayout.Rect r){return "["+r.x()+","+r.y()+","+r.width()+","+r.height()+"]";}
    public static void main(String[] args){
        System.out.print("[");boolean first=true;
        for(int[] size:new int[][]{{320,240},{512,278},{640,360},{1024,556}})for(String kind:new String[]{"cartridge","cover","library","picker","backend","skin","catalog"}){
            if(!first)System.out.print(",");first=false;int toolbar=kind.equals("backend")||kind.equals("skin")||kind.equals("catalog")?1:2;var b=DeviceLayout.browser(size[0],size[1],toolbar);
            System.out.print("{\"kind\":\""+kind+"\",\"width\":"+size[0]+",\"height\":"+size[1]+",\"split\":"+b.split());
            System.out.print(",\"panel\":"+rect(b.panel())+",\"toolbar\":"+rect(b.toolbar())+",\"list\":"+rect(b.list())+",\"details\":"+rect(b.details()));
            System.out.print(",\"primary\":"+rect(b.primary())+",\"navigation\":"+rect(b.navigation())+",\"status\":"+rect(b.status())+",\"rows\":[");
            for(int i=0;i<b.rows();i++){if(i>0)System.out.print(",");System.out.print(rect(b.row(i)));}System.out.print("]}");
        }System.out.println("]");
    }
}
