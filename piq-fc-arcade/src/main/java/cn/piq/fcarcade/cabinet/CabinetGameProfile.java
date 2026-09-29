package cn.piq.fcarcade.cabinet;

/** Administrator-supplied presentation, never core capabilities or content identity. */
public record CabinetGameProfile(String name,int players,Orientation orientation,Aspect aspect,int revision) {
    public enum Orientation { UNKNOWN,LANDSCAPE,PORTRAIT;
        public String label(){return switch(this){case UNKNOWN->"屏幕未标注";case LANDSCAPE->"横屏";case PORTRAIT->"竖屏";};}
    }
    public enum Aspect { CORE,FOUR_THREE,THREE_FOUR,SIXTEEN_NINE,SQUARE,FILL;
        public String label(){return switch(this){case CORE->"跟随核心";case FOUR_THREE->"4:3";case THREE_FOUR->"3:4";case SIXTEEN_NINE->"16:9";case SQUARE->"1:1";case FILL->"铺满屏幕";};}
        public double rawAspect(double core,int rotation,double glass){
            if(this==CORE)return core;
            double display=switch(this){case FOUR_THREE->4d/3;case THREE_FOUR->3d/4;case SIXTEEN_NINE->16d/9;case SQUARE->1;case FILL->glass;default->throw new AssertionError();};
            if(!Double.isFinite(display)||display<=0||display>32)throw new IllegalArgumentException("Invalid display aspect");
            return (Math.floorMod(rotation,4)&1)==0?display:1/display;
        }
    }
    public static final CabinetGameProfile EMPTY=new CabinetGameProfile("",0,Orientation.UNKNOWN,Aspect.CORE,0);
    public CabinetGameProfile {
        if(name==null||name.length()>64||name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c=='§')
                ||players<0||players>4||orientation==null||aspect==null||revision<0)throw new IllegalArgumentException("Invalid game profile");
        name=name.strip();
    }
    public String label(String file){return name.isEmpty()?file:name;}
    public String summary(){return (players==0?"人数未标注":players+"人")+" · "+orientation.label()+" · "+aspect.label();}
    public CabinetGameProfile next(){if(revision==Integer.MAX_VALUE)throw new IllegalStateException("Profile revision exhausted");return new CabinetGameProfile(name,players,orientation,aspect,revision+1);}
}
