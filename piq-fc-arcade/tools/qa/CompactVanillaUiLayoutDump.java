import cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import java.lang.reflect.RecordComponent;

/** Actual production layout only; no Minecraft classes, fake geometry, or screen lifecycle. */
public final class CompactVanillaUiLayoutDump {
    private static String rect(DeviceLayout.Rect r) {
        return "["+r.x()+","+r.y()+","+r.width()+","+r.height()+"]";
    }
    public static void main(String[] args)throws Exception {
        StringBuilder out=new StringBuilder("[");
        for(int[] size:new int[][]{{320,240},{512,278},{1024,556}}) {
            if(out.length()>1)out.append(',');
            DeviceLayout.Browser b=DeviceLayout.browser(size[0],size[1],2);
            CartridgeWorkbenchLayout w=CartridgeWorkbenchLayout.of(b);
            out.append("{\"width\":").append(size[0]).append(",\"height\":").append(size[1])
               .append(",\"supported\":").append(b.supported()).append(",\"split\":").append(b.split());
            for(RecordComponent c:DeviceLayout.Browser.class.getRecordComponents())
                if(c.getType()==DeviceLayout.Rect.class)out.append(",\"").append(c.getName()).append("\":").append(rect((DeviceLayout.Rect)c.getAccessor().invoke(b)));
            out.append(",\"primary\":").append(rect(b.primary())).append(",\"rows\":[");
            for(int i=0;i<b.rows();i++){if(i>0)out.append(',');out.append(rect(b.row(i)));}
            out.append("],\"workbench\":{");int at=0;
            for(RecordComponent c:CartridgeWorkbenchLayout.class.getRecordComponents()) {
                if(c.getType()!=DeviceLayout.Rect.class)throw new AssertionError("Unexpected workbench component");
                if(at++>0)out.append(',');out.append('"').append(c.getName()).append("\":").append(rect((DeviceLayout.Rect)c.getAccessor().invoke(w)));
            }
            if(at!=14)throw new AssertionError("All 14 workbench rectangles required");
            out.append("}}");
        }
        System.out.println(out.append(']'));
    }
}
