package cn.piq.retro.client;

import java.util.*;
import java.util.function.IntPredicate;

/** Pure per-owner edge/mode state; no network, Minecraft binding edits or gamepad ownership. */
public final class KeyboardControlState {
    public enum Mode { PARALLEL,FREE,LOCKED }
    private Mode mode;private boolean active,armed;private final Set<Integer> down=new HashSet<>();
    private int[][] bindings;
    public KeyboardControlState(KeyboardConfig.Preset preset,int[][] keys){configure(preset,keys);}
    public void configure(KeyboardConfig.Preset preset,int[][] keys){Objects.requireNonNull(preset);mode=Mode.PARALLEL;bindings=copy(keys);pause();}
    public boolean bindings(int[][] keys){if(Arrays.deepEquals(bindings,keys))return false;bindings=copy(keys);pause();return true;}
    private static int[][] copy(int[][] keys){return Arrays.stream(keys).map(int[]::clone).toArray(int[][]::new);}
    public Mode mode(){return mode;}
    public String status(String toggleKey){return switch(mode){
        case FREE,PARALLEL->"手持操作：游戏功能键交给模拟器，可自由移动；冲突方向键仅移动人物。按 "+toggleKey+" 锁定移动";
        case LOCKED->"移动已锁定：游戏方向和功能键交给模拟器；保留鼠标、Esc 和无关世界快捷键。按 "+toggleKey+" 解锁移动";
    };}
    public boolean enabled(){return active;}
    public boolean armed(){return enabled()&&armed;}
    private static boolean usable(int key){return key>=0||key<=-1000;}
    public boolean gameKey(int key){for(int[] row:bindings)for(int value:row)if(usable(value)&&value==key)return true;return false;}
    /** Native NES/SFC/arcade direction bits are all 4..7. Function aliases win over directions. */
    public boolean directionOnly(int key){boolean direction=false;for(int bit=0;bit<bindings.length;bit++)for(int value:bindings[bit])if(usable(value)&&value==key){if(bit<4||bit>7)return false;direction=true;}return direction;}
    public boolean captures(int key,boolean movement){return gameKey(key)&&(!directionOnly(key)||!movement||mode==Mode.LOCKED);}
    public void toggle(){mode=mode==Mode.LOCKED?Mode.PARALLEL:Mode.LOCKED;pause();}
    public boolean pause(){boolean changed=active||armed||!down.isEmpty();active=false;armed=false;down.clear();return changed;}
    public boolean activate(boolean allowed,IntPredicate physical){
        return activate(allowed,physical,key->false);
    }
    public boolean activate(boolean allowed,IntPredicate physical,IntPredicate movement){
        if(!allowed)return pause();
        active=true;
        if(!armed){boolean neutral=true;for(int[] row:bindings)for(int key:row)if(captures(key,movement.test(key))&&physical.test(key))neutral=false;armed=neutral;down.clear();}
        return false;
    }
    public boolean key(int key,int action){return key(key,action,false);}
    public boolean key(int key,int action,boolean movement){if(action==0)return down.remove(key);if(!captures(key,movement)||!armed())return false;return action==1&&down.add(key);}
    public int mask(int fallback){return mask(fallback,key->false);}
    public int mask(int fallback,IntPredicate movement){if(!armed())return 0;int result=0;for(int bit=0;bit<bindings.length;bit++){boolean mouse=false;for(int key:bindings[bit]){if(usable(key)&&captures(key,movement.test(key))&&down.contains(key))result|=1<<bit;if(key<=-1000&&captures(key,movement.test(key)))mouse=true;}if(mouse&&(fallback&(1<<bit))!=0)result|=1<<bit;}return result;}
}
