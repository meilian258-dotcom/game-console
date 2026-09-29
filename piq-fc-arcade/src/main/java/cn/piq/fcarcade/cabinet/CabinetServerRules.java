package cn.piq.fcarcade.cabinet;

/** One world-wide arcade policy. Does not change home consoles or keep chunks/hosts alive. */
public record CabinetServerRules(boolean immediateOnExit, int idleSeconds, int range) {
    public static final CabinetServerRules DEFAULT = new CabinetServerRules(false,60,16);
    public CabinetServerRules {
        if(idleSeconds<0||idleSeconds>3600||!CabinetPowerSettings.validRenderDistance(range))
            throw new IllegalArgumentException("Arcade server rules");
    }
    public boolean timedShutdown(){return idleSeconds>0;}
    public int exitRange(){return range+4;}
    public boolean closeAfterExit(boolean wasControlling,boolean hasController,boolean ready){
        return immediateOnExit&&wasControlling&&!hasController&&ready;
    }
    public boolean visible(double squaredDistance){return CabinetPowerSettings.visible(squaredDistance,range);}
}
