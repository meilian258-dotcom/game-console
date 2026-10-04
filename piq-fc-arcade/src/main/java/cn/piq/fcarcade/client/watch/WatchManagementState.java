package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.cabinet.WatchNetwork;
import cn.piq.fcarcade.cabinet.WatchPreferenceState;
import java.util.*;

/** Pure connection/sequence gate and bounded, acknowledged preference outbox. */
public final class WatchManagementState {
    public record Handle(long epoch,WatchPreferenceState.Source source,long revision,UUID lease) {
        public Handle{Objects.requireNonNull(source);Objects.requireNonNull(lease);if(epoch<1||revision<1)throw new IllegalArgumentException("watch handle");}
    }
    private record Change(Handle handle,boolean paused) {}
    private Object connection;
    private long epoch,sequence;
    private int sentAt,nextSendAt;
    private WatchNetwork.Preference flight;
    private final Map<WatchPreferenceState.Source,Handle> paused=new LinkedHashMap<>();
    private final Map<WatchPreferenceState.Source,Boolean> desired=new HashMap<>();
    private final Map<WatchPreferenceState.Source,Change> queue=new LinkedHashMap<>();
    private String status="只管理本机旁观；不关机、不归还手柄、不修改存档。";
    public boolean connection(Object next){
        if(next==connection)return false;
        connection=next;epoch++;sequence=0;flight=null;paused.clear();desired.clear();queue.clear();sentAt=nextSendAt=0;
        status="暂停仅对本次连接、这一局生效；换服或重连清除。";return true;
    }
    public long epoch(){return epoch;}
    public boolean current(Handle h){return connection!=null&&h!=null&&h.epoch()==epoch;}
    public boolean canResume(Handle h){return current(h)&&h.equals(paused.get(h.source()));}
    public boolean paused(WatchPreferenceState.Source source){return paused.containsKey(source);}
    public boolean resumePending(WatchPreferenceState.Source source){return Boolean.FALSE.equals(desired.get(source));}
    public List<Handle> paused(){return List.copyOf(paused.values());}
    public String status(){return status;}
    public int pending(){return queue.size()+(flight==null?0:1);}
    public static boolean released(boolean preparationDone,boolean runtimeDone,boolean nativeHeld){return preparationDone&&runtimeDone&&!nativeHeld;}
    public boolean change(Handle h,boolean pause){
        if(!current(h))return false;
        if(!pause&&!canResume(h))return false;
        if(pause&&!paused.containsKey(h.source())&&paused.size()>=WatchPreferenceState.LIMIT){status="本连接已暂停 64 路；请先恢复部分来源。";return false;}
        if(!queue.containsKey(h.source())&&queue.size()>=WatchPreferenceState.LIMIT*2){status="旁观偏好队列繁忙，请稍后再试。";return false;}
        if(pause)paused.put(h.source(),h);
        desired.put(h.source(),pause); // Resume keeps its row/local guard until an accepted, exact ACK.
        queue.put(h.source(),new Change(h,pause));
        status=pause?"本机已暂停这一局，正在确认服务器退订。":"已请求恢复；仍由服务器按距离、额度和权限发现。";
        return true;
    }
    /** A delayed/new lease for an explicitly paused source cannot restart its core. */
    public boolean granted(Handle h){return current(h)&&paused(h.source())&&!resumePending(h.source())&&change(h,true);}
    /** One in flight and at least five ticks between sends; restore-all is queued, never truncated. */
    public WatchNetwork.Preference next(int tick){
        if(connection==null)return null;
        if(flight!=null&&tick-sentAt>=100){
            if(!flight.paused()&&!queue.containsKey(flight.source()))desired.put(flight.source(),true);
            flight=null;status="服务器偏好确认超时；本机暂停仍保留，可点击恢复重试。";
        }
        if(flight!=null||tick<nextSendAt||queue.isEmpty())return null;
        var iterator=queue.entrySet().iterator();var change=iterator.next().getValue();iterator.remove();
        var h=change.handle();flight=new WatchNetwork.Preference(++sequence,h.source(),h.revision(),h.lease(),change.paused());
        sentAt=tick;nextSendAt=tick+5;return flight;
    }
    public boolean acknowledge(WatchNetwork.PreferenceResult response){
        if(flight==null||flight.sequence()!=response.sequence()||!flight.source().equals(response.source())||flight.paused()!=response.paused())return false;
        flight=null;
        // An older pause ACK may finish after the user has already queued resume (or vice versa).
        if(Objects.equals(desired.get(response.source()),response.paused())&&!queue.containsKey(response.source())){
            status=response.reason();
            if(!response.paused()){
                if(response.accepted()){paused.remove(response.source());desired.remove(response.source());}
                else{desired.put(response.source(),true);status+="；本机仍暂停，可再次恢复。";}
            }
        }
        return true;
    }
}
