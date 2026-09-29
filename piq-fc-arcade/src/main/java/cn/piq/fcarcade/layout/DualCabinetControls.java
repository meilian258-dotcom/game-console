package cn.piq.fcarcade.layout;

import java.util.List;

/** Exact user group pivots in model units; presentation only, never writes core inputs. */
public final class DualCabinetControls {
    /** Input masks belong to their backend; animation must not apply core-adapter remapping. */
    public enum InputLayout { NES, SFC, ARCADE }
    public record Part(String name,int player,int button,double x,double y,double z) {
        public boolean joystick(){return button<0;}
    }
    public record Motion(double pressY,double tiltX,double tiltZ) {}
    public static final List<Part> PARTS=List.of(
            new Part("p1_button_1",0,0,17.285,13.3825,1.397),
            new Part("p1_button_2",0,1,16.053,13.3825,1.529),
            new Part("p1_button_3",0,2,14.821,13.3825,1.397),
            new Part("p1_button_4",0,3,17.285,13.3825,2.75),
            new Part("p1_button_5",0,4,16.053,13.3825,2.882),
            new Part("p1_button_6",0,5,14.821,13.3825,2.75),
            new Part("p1_start",0,6,18.759,13.333,1.386),
            new Part("p1_joystick",0,-1,20.86,13.3495,2.97),
            new Part("p2_button_1",1,0,5.285,13.3825,1.397),
            new Part("p2_button_2",1,1,4.053,13.3825,1.529),
            new Part("p2_button_3",1,2,2.821,13.3825,1.397),
            new Part("p2_button_4",1,3,5.285,13.3825,2.75),
            new Part("p2_button_5",1,4,4.053,13.3825,2.882),
            new Part("p2_button_6",1,5,2.821,13.3825,2.75),
            new Part("p2_start",1,6,6.759,13.333,1.386),
            new Part("p2_joystick",1,-1,8.86,13.3495,2.97));
    private static final int[] SFC_BITS={0,8,1,9,10,11,3};
    private static final int[] ARCADE_BITS={0,1,8,9,10,11,3};
    private static final int[] NES_BITS={1,0,-1,-1,-1,-1,3};
    private DualCabinetControls(){}
    public static InputLayout layoutForBackend(String backend){
        return "piq_fc_arcade:nes".equals(backend)?InputLayout.NES
                :"piq_sfc_home:sfc".equals(backend)?InputLayout.SFC:InputLayout.ARCADE;
    }
    /** Compatibility for callers supplying the original NES/SFC bit layouts. */
    public static Motion motion(Part part,int mask,boolean nes){
        return motion(part,mask,nes?InputLayout.NES:InputLayout.SFC);
    }
    public static Motion motion(Part part,int mask,InputLayout layout){
        if(mask<0)return new Motion(0,0,0);
        if(part.joystick()){
            int horizontal=((mask&(1<<7))!=0?1:0)-((mask&(1<<6))!=0?1:0);
            int vertical=((mask&(1<<4))!=0?1:0)-((mask&(1<<5))!=0?1:0);
            // Front is -Z; viewer-right is -X. Fixed rubber bases never move.
            return new Motion(0,vertical*8D,horizontal*8D);
        }
        int[] bits=switch(layout){case NES->NES_BITS;case SFC->SFC_BITS;case ARCADE->ARCADE_BITS;};
        int bit=bits[part.button()];
        return new Motion(bit>=0&&(mask&(1<<bit))!=0?(part.button()==6?-.08:-.12):0,0,0);
    }
}
