package cn.piq.fcarcade.client.ui;

/** Shared deterministic GUI-pixel geometry, also consumed directly by offline layout previews. */
public final class DeviceLayout {
    public static final int ROW_STEP=22,ROW_HEIGHT=20;
    public record Rect(int x,int y,int width,int height){
        public int right(){return x+width;}public int bottom(){return y+height;}
        public boolean contains(Rect r){return r.x>=x&&r.y>=y&&r.right()<=right()&&r.bottom()<=bottom();}
        public boolean overlaps(Rect r){return x<r.right()&&right()>r.x&&y<r.bottom()&&bottom()>r.y;}
    }
    public record Browser(boolean supported,boolean split,Rect panel,Rect toolbar,Rect list,Rect details,Rect navigation,Rect status,int rows){
        public Rect row(int index){return new Rect(list.x()+4,list.y()+4+index*ROW_STEP,list.width()-8,ROW_HEIGHT);}
        public Rect primary(){return new Rect(details.x()+6,details.bottom()-26,details.width()-12,20);}
    }
    private DeviceLayout(){}
    /** toolbarRows is 1 or 2; the compact layout keeps selected details above a separate status strip. */
    public static Browser browser(int width,int height,int toolbarRows){
        if(toolbarRows<1||toolbarRows>2)throw new IllegalArgumentException("One or two toolbar rows");
        int w=Math.max(1,Math.min(Math.min(420,width-32),Math.max(280,Math.round(width*.80f))));
        int h=Math.max(1,Math.min(Math.min(260,height-32),Math.max(208,Math.round(height*.81f))));
        int x=(width-w)/2,y=(height-h)/2;Rect panel=new Rect(x,y,w,h);
        boolean supported=width>=320&&height>=240;
        int top=y+40,toolbarHeight=toolbarRows*24-4,bodyY=top+toolbarHeight+4;
        Rect toolbar=new Rect(x+10,top,w-20,toolbarHeight);
        Rect nav=new Rect(x+10,y+h-48,w-20,20),status=new Rect(x+10,y+h-23,w-20,18);
        int bodyHeight=Math.max(1,nav.y()-8-bodyY),bodyWidth=Math.max(1,w-20);
        int dw=Math.min(156,Math.max(112,bodyWidth*37/100));
        Rect list=new Rect(x+10,bodyY,bodyWidth-dw-8,bodyHeight);
        Rect details=new Rect(list.right()+8,bodyY,dw,bodyHeight);
        int rows=Math.max(1,(list.height()-8+2)/ROW_STEP);
        return new Browser(supported,true,panel,toolbar,list,details,nav,status,rows);
    }
}
