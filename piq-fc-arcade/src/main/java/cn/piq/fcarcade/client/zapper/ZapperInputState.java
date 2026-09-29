package cn.piq.fcarcade.client.zapper;

/** Focus/lease transitions require a physical mouse release before rearming. */
public final class ZapperInputState {
    private boolean armed,trigger;
    public boolean sample(boolean allowed,boolean physicalDown) {
        if (!allowed) {clear();return false;}
        if (!armed&&!physicalDown) armed=true;
        trigger=armed&&physicalDown;return trigger;
    }
    public boolean trigger(){return trigger;}
    /** Authorization is rechecked by the renderer without sampling or rearming input. */
    public boolean visualTrigger(boolean authorized){return authorized&&armed&&trigger;}
    public boolean armed(){return armed;}
    public void clear(){armed=false;trigger=false;}
}
