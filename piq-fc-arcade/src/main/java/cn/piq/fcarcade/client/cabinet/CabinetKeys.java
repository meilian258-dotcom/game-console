package cn.piq.fcarcade.client.cabinet;

import java.util.HashSet;
import java.util.Set;

/** GLFW values are constants here so edge/focus behavior can be tested without a window. */
public final class CabinetKeys {
    private static final int[] P1={74,85,259,257,265,264,263,262,75,73,79,80};
    private static final int[] P2={70,82,53,50,87,83,65,68,71,84,89,72};
    private final Set<Integer> down=new HashSet<>();
    public boolean press(int key){return known(key)&&down.add(key);}
    public boolean release(int key){return down.remove(key);}
    public void clear(){down.clear();}
    public int player1(){return mask(P1);}
    public int player2(){return mask(P2);}
    private int mask(int[] keys){int value=0;for(int i=0;i<keys.length;i++)if(down.contains(keys[i]))value|=1<<i;return value;}
    private static boolean known(int key){for(int k:P1)if(k==key)return true;for(int k:P2)if(k==key)return true;return false;}
}
