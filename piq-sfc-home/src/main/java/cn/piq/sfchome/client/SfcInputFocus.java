package cn.piq.sfchome.client;

/** Matches FC: do not replay a keyboard/gamepad key held through a GUI or loss of focus. */
final class SfcInputFocus {
    private boolean waitingForNeutral;
    void suspend(){waitingForNeutral=true;}
    int sample(int mask){
        if(waitingForNeutral){if(mask==0)waitingForNeutral=false;return 0;}
        return mask;
    }
    void reset(){waitingForNeutral=false;}
}
