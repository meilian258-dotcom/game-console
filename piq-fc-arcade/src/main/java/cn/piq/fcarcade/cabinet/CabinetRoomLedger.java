package cn.piq.fcarcade.cabinet;

import java.util.*;

/** Server-thread-only fixed seats; SERVER_MEDIA retains its worker identity when players leave. */
final class CabinetRoomLedger<K> {
    static final int MAX_ROOMS=4, INPUT_TIMEOUT=40, HEARTBEAT_TIMEOUT=80;
    static final class Member {
        final UUID id,player,room;final int port;
        long expires,lastInput,sequence=-1,forwardSequence,tokenTick;int mask,inputTokens=16;boolean inputSeen,rateLimited,controlling=true;Object connection;
        Member(UUID id,UUID player,UUID room,int port,long now){this.id=id;this.player=player;this.room=room;this.port=port;expires=now+HEARTBEAT_TIMEOUT;lastInput=now;tokenTick=now;}
        boolean takeInput(long now){
            long elapsed=Math.max(0,now-tokenTick);
            inputTokens=elapsed>=8?16:Math.min(16,inputTokens+(int)elapsed*2);tokenTick=now;
            if(inputTokens==0){rateLimited=true;return false;}inputTokens--;return true;
        }
    }
    static final class Room<K> {
        final UUID id,streamHostId,ownerId;final K target;final String backend;final int capacity;final Member[] members;
        boolean autoPowerOff,immediateOnExit;int idleShutdownSeconds=CabinetPowerSettings.DEFAULT_SECONDS;
        boolean ready,coinRequired,coinReleaseSupported;long coinSequence;CabinetSyncMode mode=CabinetSyncMode.MEDIA;
        Room(UUID id,K target,String backend,int capacity,Member host){this.id=id;streamHostId=host.id;ownerId=host.player;this.target=target;this.backend=backend;this.capacity=capacity;members=new Member[capacity];members[0]=host;}
        Member host(){if(mode==CabinetSyncMode.SERVER_MEDIA)for(Member member:members){if(member!=null)return member;}return members[0];}
        boolean hasController(){for(Member member:members)if(member!=null&&member.controlling)return true;return false;}
    }
    record Change(UUID room,UUID member,int port,long sequence,int mask,boolean reset){}
    private final Map<UUID,Room<K>> rooms=new LinkedHashMap<>();
    private final Map<UUID,Member> members=new HashMap<>(),players=new HashMap<>();
    Room<K> open(UUID player,K target,String backend,int capacity,long now){
        Objects.requireNonNull(player);Objects.requireNonNull(target);Objects.requireNonNull(backend);
        if(capacity<1||capacity>4||rooms.size()>=MAX_ROOMS||players.containsKey(player)||target(target)!=null)return null;
        UUID id=UUID.randomUUID();Member host=new Member(id,player,id,0,now);Room<K> room=new Room<>(id,target,backend,capacity,host);
        rooms.put(id,room);members.put(id,host);players.put(player,host);return room;
    }
    Member join(UUID player,Room<K> room,long now){
        return join(player,room,now,room!=null&&room.mode==CabinetSyncMode.SERVER_MEDIA?0:1,room==null?0:room.capacity);
    }
    Member join(UUID player,Room<K> room,long now,int firstPort,int endPort){
        Objects.requireNonNull(player);
        if(room==null||rooms.get(room.id)!=room||!room.ready||players.containsKey(player)||firstPort<(room.mode==CabinetSyncMode.SERVER_MEDIA?0:1)||endPort>room.capacity||firstPort>=endPort)return null;
        for(int port=firstPort;port<endPort;port++)if(room.members[port]==null){
            Member member=new Member(UUID.randomUUID(),player,room.id,port,now);room.members[port]=member;members.put(member.id,member);players.put(player,member);return member;
        }
        return null;
    }
    Room<K> get(UUID id){return rooms.get(id);}
    Room<K> target(K target){return rooms.values().stream().filter(r->r.target.equals(target)).findFirst().orElse(null);}
    Member member(UUID id){return members.get(id);}
    Member player(UUID player){return players.get(player);}
    List<Room<K>> all(){return List.copyOf(rooms.values());}
    List<Member> allMembers(){return List.copyOf(members.values());}
    /** Only current occupied seats, never applicants, spectators or the original hosted owner. */
    String occupantNames(Room<K> room,java.util.function.Function<Member,String> currentName){
        if(room==null||rooms.get(room.id)!=room)return "";
        var names=new LinkedHashSet<String>();
        for(Member member:room.members)if(member!=null&&members.get(member.id)==member){
            String name=currentName.apply(member);
            if(name!=null&&!name.isBlank())names.add(name);
        }
        return String.join("、",names);
    }
    Member valid(UUID player,UUID room,UUID id,long now){
        Member m=members.get(id);return m!=null&&!m.rateLimited&&m.player.equals(player)&&m.room.equals(room)&&now<m.expires?m:null;
    }
    boolean ready(UUID player,UUID room,UUID id,long now){
        Member m=valid(player,room,id,now);if(m==null||m.port!=0)return false;rooms.get(room).ready=true;return true;
    }
    boolean heartbeat(UUID player,UUID id,long now){Member m=members.get(id);if(m==null||!m.player.equals(player)||now>=m.expires)return false;m.expires=now+HEARTBEAT_TIMEOUT;return true;}
    Change input(UUID player,UUID room,UUID id,long sequence,int mask,long now){
        Member m=valid(player,room,id,now);if(m==null||!rooms.get(room).ready||sequence<0||sequence<=m.sequence||mask<0||mask>4095)return null;
        if(!m.takeInput(now))return null;
        mask=m.controlling?CabinetCoinPolicy.filter(rooms.get(room).coinRequired,mask):0;
        m.sequence=sequence;m.lastInput=now;m.inputSeen=true;
        if(mask==m.mask)return null;
        m.mask=mask;return new Change(room,id,m.port,++m.forwardSequence,mask,false);
    }
    Change resetInput(UUID player,UUID room,UUID id,long sequence,long now){
        Member m=valid(player,room,id,now);if(m==null||!rooms.get(room).ready||sequence<0||sequence<=m.sequence)return null;
        if(!m.takeInput(now))return null;
        m.sequence=sequence;m.lastInput=now;return reset(m);
    }
    Change reset(Member member){
        if(member==null||members.get(member.id)!=member)return null;
        member.mask=0;member.inputSeen=false;return new Change(member.room,member.id,member.port,++member.forwardSequence,0,true);
    }
    List<Change> silence(long now){
        List<Change> changes=new ArrayList<>();
        for(Member m:members.values())if(m.inputSeen&&now-m.lastInput>=INPUT_TIMEOUT)changes.add(reset(m));
        return changes;
    }
    /** Player-host departure closes the room; server-hosted departures keep the other exact ports. */
    List<Member> remove(UUID player,UUID id){
        Member m=members.get(id);if(m==null||!m.player.equals(player))return List.of();Room<K> room=rooms.get(m.room);
        List<Member> removed=new ArrayList<>();
        if(room.mode==CabinetSyncMode.SERVER_MEDIA){
            room.members[m.port]=null;removed.add(m);
            if(room.host()==null)rooms.remove(room.id);
        }
        else if(m.port==0){rooms.remove(room.id);for(Member seat:room.members)if(seat!=null)removed.add(seat);}
        else{room.members[m.port]=null;removed.add(m);}
        for(Member seat:removed){members.remove(seat.id);players.remove(seat.player,seat);}
        return List.copyOf(removed);
    }
    /** Device removal or fatal worker failure closes every seat regardless of hosting mode. */
    List<Member> removeRoom(UUID roomId){
        Room<K> room=rooms.remove(roomId);if(room==null)return List.of();
        List<Member> removed=new ArrayList<>();
        for(Member seat:room.members)if(seat!=null){removed.add(seat);members.remove(seat.id);players.remove(seat.player,seat);}
        Arrays.fill(room.members,null);
        return List.copyOf(removed);
    }
    /** Exact sliding 20-tick budget, including both sides of a wall-clock second boundary. */
    static final class Window {
        private final long[] ticks=new long[20];private final int[] values=new int[20];
        boolean allow(long now,int amount,int limit){
            if(now<0||amount<0||amount>limit)return false;
            int total=0;for(int i=0;i<20;i++)if(now>=ticks[i]&&now-ticks[i]<20)total+=values[i];
            if((long)total+amount>limit)return false;
            int slot=(int)(now%20);if(ticks[slot]!=now){ticks[slot]=now;values[slot]=0;}values[slot]+=amount;return true;
        }
    }
}
