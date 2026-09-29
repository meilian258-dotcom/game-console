package cn.piq.fcarcade.client.ui;

/** Pure geometry for small device forms, including the minimum 320 x 240 GUI. */
public record DeviceFormLayout(DeviceLayout.Rect panel, int rows, int stride) {
    public static DeviceFormLayout of(int width,int height,int rows) {
        int count=Math.max(1,Math.min(6,rows));
        int w=Math.max(100,Math.min(Math.min(360,width-32),Math.round(width*.80f)));
        int h=Math.max(130,Math.min(94+count*28,height-16));
        return new DeviceFormLayout(new DeviceLayout.Rect((width-w)/2,(height-h)/2,w,h),
                count,Math.max(20,Math.min(28,(h-94)/count)));
    }
    public int left(){return panel.x()+10;}
    public int bodyWidth(){return panel.width()-20;}
    public int rowY(int row){return panel.y()+44+row*stride;}
    public int fieldX(){return left()+bodyWidth()*55/100;}
    public int fieldWidth(){return left()+bodyWidth()-fieldX();}
    public int labelWidth(){return fieldX()-left()-8;}
    public int statusY(){return panel.bottom()-49;}
    public int footerY(){return panel.bottom()-30;}
}
