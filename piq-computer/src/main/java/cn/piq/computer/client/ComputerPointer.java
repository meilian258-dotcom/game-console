package cn.piq.computer.client;

/** Relative motion in logical screen pixels. Independent of GUI scale, FPS and camera sensitivity. */
final class ComputerPointer {
    private double x=320,y=240;
    void reset(){x=320;y=240;}
    void position(int x,int y){this.x=Math.clamp(x,0,639);this.y=Math.clamp(y,0,479);}
    void move(double dx,double dy,double sensitivity){
        if(!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(sensitivity))return;
        double scale=Math.clamp(sensitivity,.25,2);
        x=Math.clamp(x+dx*scale,0,639);y=Math.clamp(y+dy*scale,0,479);
    }
    int x(){return (int)Math.round(x);}
    int y(){return (int)Math.round(y);}
}
