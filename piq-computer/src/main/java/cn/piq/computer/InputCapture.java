package cn.piq.computer;
import java.util.*;
/** Arm only after the entry click/held keys are released; drain captured presses on exit. */
public final class InputCapture {
    private boolean active,armed;
    private final Set<Integer> keys=new HashSet<>(),buttons=new HashSet<>();
    public void begin(Set<Integer> downKeys,Set<Integer> downButtons){active=true;armed=false;keys.clear();keys.addAll(downKeys);buttons.clear();buttons.addAll(downButtons);}
    public void end(){active=false;armed=false;}
    public boolean active(){return active;}
    public boolean armed(){return active&&armed;}
    public boolean needsPoll(){return active||!keys.isEmpty()||!buttons.isEmpty();}
    public void sample(Set<Integer> k,Set<Integer> b){keys.retainAll(k);buttons.retainAll(b);if(active&&!armed&&k.isEmpty()&&b.isEmpty())armed=true;}
    public boolean key(int key,int action){return event(keys,key,action);}
    public boolean button(int button,int action){return event(buttons,button,action);}
    private boolean event(Set<Integer> held,int key,int action){boolean capture=active||held.contains(key);if(!capture)return false;if(action==0)held.remove(key);else held.add(key);return true;}
}
