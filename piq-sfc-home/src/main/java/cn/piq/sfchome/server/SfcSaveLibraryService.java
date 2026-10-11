// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.fcarcade.home.CartridgeSaveNetwork;
import cn.piq.fcarcade.home.flow.HomeLaunchIo;
import cn.piq.fcarcade.netplay.NetplaySaveServer;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Independent read-only lease, issued exclusively by the live SFC computer workbench. */
final class SfcSaveLibraryService {
    static final String SYSTEM="piq_sfc_home:sfc";
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private SfcSaveLibraryService(){}

    static void register(){CartridgeSaveNetwork.registerServerHandlers(SYSTEM,
            (p,r)->reject(p,r.editorToken(),"请从 SFC 卡带工作台打开存档库"),SfcSaveLibraryService::request);}

    static void reject(ServerPlayer player,UUID editor,String message){
        CartridgeSaveNetwork.reply(player,new CartridgeSaveNetwork.Reply(editor,SYSTEM,"",message,List.of(),"","",null,true,editor));
    }

    static void open(ServerPlayer player,UUID editor,UUID card,String rom,BooleanSupplier authority){
        MinecraftServer server=player.getServer();
        if(server==null||!server.isSameThread()||card==null||!authority.getAsBoolean()){
            reject(player,editor,"电脑或卡带授权失效，请重新打开工作台");return;
        }
        State state=STATES.computeIfAbsent(server,key->new State());
        Lease old=state.leases.values().stream().filter(l->l.player==player).findFirst().orElse(null);
        if(old!=null||state.leases.size()>=4){reject(player,editor,"存档库已有请求或正忙，请稍后重试");return;}
        var catalog=new SfcSaveLibraryCatalog(player.getUUID(),card,rom,(owner,identity)->NetplaySaveServer.directory(server,owner,identity));
        Lease lease=new Lease(player,editor,rom,authority,catalog,state.tick);state.leases.put(lease.token,lease);
        refresh(server,state,lease);
    }

    private static boolean valid(MinecraftServer server,State state,Lease lease){
        return STATES.get(server)==state&&state.leases.get(lease.token)==lease
                &&state.tick<lease.expires&&!lease.player.hasDisconnected()&&lease.authority.getAsBoolean();
    }

    private static void request(ServerPlayer player,CartridgeSaveNetwork.Request request){
        MinecraftServer server=player.getServer();if(server==null||!server.isSameThread())return;
        State state=STATES.get(server);Lease lease=state==null?null:state.leases.get(request.token());
        if(lease==null||lease.player!=player)return;
        if(request.operation()==CartridgeSaveNetwork.CLOSE){remove(state,lease);return;}
        if(!valid(server,state,lease)){end(state,lease,"电脑或卡带授权失效，请重新打开工作台");return;}
        if(lease.loading||state.tick-lease.lastAction<20)return;
        lease.lastAction=state.tick;
        if(request.operation()!=CartridgeSaveNetwork.REFRESH){reply(server,lease,"SFC 存档库为只读，不支持重命名或删除；原档未改动");return;}
        refresh(server,state,lease);
    }

    private static void refresh(MinecraftServer server,State state,Lease lease){
        lease.loading=true;lease.lastAction=state.tick;
        HomeLaunchIo.read(server,lease.catalog::inspect,listing->{
            if(!valid(server,state,lease)){if(state.leases.get(lease.token)==lease)end(state,lease,"电脑或卡带授权失效，请重新打开工作台");return;}
            lease.loading=false;lease.rows=listing.rows();reply(server,lease,listing.message());
        },reason->{
            if(!valid(server,state,lease)){if(state.leases.get(lease.token)==lease)end(state,lease,"电脑或卡带授权失效，请重新打开工作台");return;}
            lease.loading=false;reply(server,lease,"存档目录读取失败，请稍后刷新；原档未改动");
        });
    }

    private static void reply(MinecraftServer server,Lease lease,String message){
        var rows=lease.rows.stream().map(row->new CartridgeSaveNetwork.Entry(row.id(),row.version(),row.name(),row.owner(),row.source(),
                row.modified(),row.bytes(),NetplaySaveServer.busy(server,row.slotKey()),false)).toList();
        CartridgeSaveNetwork.reply(lease.player,new CartridgeSaveNetwork.Reply(lease.token,SYSTEM,lease.rom,message,rows,"","",null,false,lease.editor));
        lease.opened=true;
    }

    private static void remove(State state,Lease lease){state.leases.remove(lease.token,lease);CartridgeSaveNetwork.forgetRoute(lease.token);}
    private static void end(State state,Lease lease,String message){
        remove(state,lease);
        if(lease.player.hasDisconnected())return;
        if(!lease.opened){reject(lease.player,lease.editor,message);return;}
        CartridgeSaveNetwork.reply(lease.player,new CartridgeSaveNetwork.Reply(lease.token,SYSTEM,lease.rom,message,List.of(),"","",null,true,lease.editor));
    }
    static void tick(MinecraftServer server){
        State state=STATES.get(server);if(state==null)return;state.tick++;
        if(state.tick%20!=0)return;
        for(Lease lease:List.copyOf(state.leases.values()))if(!valid(server,state,lease))end(state,lease,"存档浏览授权失效或超时，请重新打开工作台");
    }
    static void close(MinecraftServer server){State state=STATES.remove(server);if(state!=null){for(Lease lease:state.leases.values())CartridgeSaveNetwork.forgetRoute(lease.token);state.leases.clear();}}
    private static final class State {long tick;final Map<UUID,Lease> leases=new HashMap<>();}
    private static final class Lease {
        final UUID token=UUID.randomUUID(),editor;final ServerPlayer player;final String rom;
        final BooleanSupplier authority;final SfcSaveLibraryCatalog catalog;final long expires;
        long lastAction;boolean loading,opened;List<SfcSaveLibraryCatalog.Row> rows=List.of();
        Lease(ServerPlayer player,UUID editor,String rom,BooleanSupplier authority,SfcSaveLibraryCatalog catalog,long tick){
            this.player=player;this.editor=editor;this.rom=rom;this.authority=authority;this.catalog=catalog;expires=tick+1200;
        }
    }
}
