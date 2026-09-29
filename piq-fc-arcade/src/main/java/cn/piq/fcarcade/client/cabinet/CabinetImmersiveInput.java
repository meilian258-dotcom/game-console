package cn.piq.fcarcade.client.cabinet;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** One local player only. Never consumes Minecraft movement or mouse bindings. */
public final class CabinetImmersiveInput {
    private static final int[] P1={74,85,259,257,265,264,263,262,75,73,79,80};
    private final Set<Integer> down=new HashSet<>();
    private boolean active;
    private boolean armed;
    /** Returns true once when the host must forcibly flush pending emulator input. */
    public boolean activate(boolean allowed){
        return activate(allowed,key->false);
    }
    /**
     * Physical state is consulted only while waiting to resume, never used to
     * manufacture an input edge. Menus, focus loss and a new session require
     * all twelve game keys to be up before accepting another PRESS.
     */
    public boolean activate(boolean allowed,IntPredicate physicalDown){
        boolean release=active&&!allowed;
        active=allowed;
        if(!allowed){down.clear();armed=false;}
        else if(!armed){
            down.clear();
            boolean neutral=true;
            for(int key:P1)if(physicalDown.test(key)){neutral=false;break;}
            armed=neutral;
        }
        return release;
    }
    public boolean armed(){return active&&armed;}
    /** GLFW RELEASE=0, PRESS=1; repeats never resurrect cleared keys. */
    public boolean key(int key,int action){
        if(!armed())return false;
        if(action==0)return down.remove(key);
        if(action!=1)return false;
        for(int known:P1)if(known==key)return down.add(key);
        return false;
    }
    public int mask(){int mask=0;for(int i=0;i<P1.length;i++)if(down.contains(P1[i]))mask|=1<<i;return mask;}
    public void reset(){active=false;armed=false;down.clear();}
}
