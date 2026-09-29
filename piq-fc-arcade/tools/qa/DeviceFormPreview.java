import cn.piq.fcarcade.client.ui.DeviceFormLayout;
import cn.piq.fcarcade.client.ui.DeviceLayout;
public final class DeviceFormPreview {
    private static String rect(DeviceLayout.Rect r){return "["+r.x()+","+r.y()+","+r.width()+","+r.height()+"]";}
    public static void main(String[] args){
        System.out.print("[");boolean first=true;
        for(int[] size:new int[][]{{320,240},{512,278},{640,360}})for(String kind:new String[]{"settings","leaderboard","slots"}){
            if(!first)System.out.print(",");first=false;int rows=kind.equals("settings")?4:kind.equals("slots")?5:6;var f=DeviceFormLayout.of(size[0],size[1],rows);
            System.out.print("{\"kind\":\""+kind+"\",\"width\":"+size[0]+",\"height\":"+size[1]+",\"panel\":"+rect(f.panel()));
            System.out.print(",\"left\":"+f.left()+",\"bodyWidth\":"+f.bodyWidth()+",\"fieldX\":"+f.fieldX()+",\"fieldWidth\":"+f.fieldWidth()+",\"labelWidth\":"+f.labelWidth()+",\"statusY\":"+f.statusY()+",\"footerY\":"+f.footerY()+",\"rowY\":[");
            for(int i=0;i<rows;i++){if(i>0)System.out.print(",");System.out.print(f.rowY(i));}System.out.print("]}");
        }System.out.println("]");
    }
}
