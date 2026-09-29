package cn.piq.fcarcade.client;

/** Reviewed key-cap bounds, in canonical model pixels (+Z buttons, -X D-pad).
 * Classification requires every vertex inside a cap, so the large printed face and red slots stay still. */
final class ControllerButtonGeometry {
    static final int BODY=0, DPAD=1, A=2, B=3, SELECT=4, START=5, TURBO_A=6, TURBO_B=7, PARTS=8;
    record Point(double x,double y,double z) {}
    private record Box(double x0,double y0,double z0,double x1,double y1,double z1) {
        boolean contains(Point p) { double e=.0001;return p.x>=x0-e&&p.x<=x1+e&&p.y>=y0-e&&p.y<=y1+e&&p.z>=z0-e&&p.z<=z1+e; }
    }
    private static final Box[] FC={null,new Box(3.31192,6.35888,8.792,5.53192,8.61888,9.074),
        new Box(11.78459,6.31356,8.794,12.85459,7.38356,9.034),new Box(10.17210,6.31356,8.794,11.24210,7.38356,9.034),
        new Box(6.62694,6.6982,8.792,7.53694,7.0082,9.014),new Box(8.36890,6.6982,8.792,9.27890,7.0082,9.014)};
    private static final Box[] SB={null,new Box(2.924,6.123692857,8.416957143,5.824571429,9.024264286,8.7614),
        new Box(11.661971429,5.815507143,8.426021429,12.8222,6.975735714,8.833914286),
        new Box(10.139171429,5.815507143,8.426021429,11.2994,6.975735714,8.833914286),
        new Box(7.002928571,5.978664286,8.416957143,8.054385714,6.486264286,8.69795),
        new Box(8.489471429,5.978664286,8.416957143,9.540928571,6.486264286,8.69795),
        new Box(11.661971429,7.737135714,8.426021429,12.8222,8.897364286,8.833914286),
        new Box(10.139171429,7.737135714,8.426021429,11.2994,8.897364286,8.833914286)};
    private ControllerButtonGeometry() {}
    static Point canonical(Point p,boolean subor,int port) { return subor?p:port==0?new Point(16-p.z,p.y,p.x):new Point(p.z,p.y,16-p.x); }
    static Point raw(Point p,boolean subor,int port) { return subor?p:port==0?new Point(p.z,p.y,16-p.x):new Point(16-p.z,p.y,p.x); }
    static int classify(Point[] vertices,boolean subor,int port) {
        Box[] boxes=subor?SB:FC;
        for(int part=1;part<boxes.length;part++) {
            // Original FC P2 has a microphone instead of select/start buttons.
            if(!subor&&port==1&&part>=SELECT)continue;
            boolean contained=true;
            for(Point p:vertices)if(!boxes[part].contains(canonical(p,subor,port))){contained=false;break;}
            if(contained)return part;
        }
        return BODY;
    }
    static Point pivot(boolean subor) { return subor?new Point(4.374285714,7.573978571,8.50):new Point(4.42192,7.48888,8.89); }
    static int mask(int part) { return switch(part) {case A,TURBO_A->1;case B,TURBO_B->2;case SELECT->4;case START->8;default->0;}; }
    static double travel(boolean subor,int part) { return part==BODY||part==DPAD?0:subor?.13:.10; }
}
