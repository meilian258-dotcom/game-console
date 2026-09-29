import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.fcarcade.cabinet.CabinetFrame;

/** Compile ONLY against the frozen alpha18 JAR to exercise the old addon ABI. */
public final class LegacyCabinetFixture implements CabinetEmulator {
    private final CabinetFrame frame=new CabinetFrame(1,1,new int[]{0x12345678},4F/3F,1,new short[]{1,-1});
    private int p1,p2;
    public boolean isReady(){return p1==0x123&&p2==0x456;}
    public String error(){return null;}
    public void offerInput(int a,int b){p1=a;p2=b;}
    public void clearInput(){p1=0;p2=0;}
    public CabinetFrame pollFrame(){return frame;}
    public void close(){}
}
