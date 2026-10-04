package cn.piq.fcarcade.home.content;

import java.nio.file.Path;
import java.util.*;

/** Source-aware rows for the currently loaded server page; physical local paths never cross the wire. */
public final class ContentCardCatalog {
    public record Choice(String name,int size,String hash,Path path,boolean serverAvailable,boolean localAvailable) {
        public Choice(String name,int size,String hash,Path path){this(name,size,hash,path,path==null,path!=null);}
        public String source(){return serverAvailable&&localAvailable?"本地 / 服务器":serverAvailable?"服务器":"本地";}
    }
    public static List<Choice> page(List<ContentCardStore.Entry> server,List<Choice> locals,String query,boolean includeLocal,boolean useServer){
        String key=query.strip().toLowerCase(Locale.ROOT);
        Map<String,Choice> localByIdentity=new LinkedHashMap<>();
        for(var local:locals)if(!local.hash().isBlank())localByIdentity.putIfAbsent(identity(local.hash(),local.size()),local);
        Map<String,Choice> rows=new LinkedHashMap<>();
        for(var entry:server){
            String id=identity(entry.hash(),entry.size());var local=localByIdentity.get(id);
            String name=entry.displayName();
            if(local!=null&&name.equals(ContentCardNames.fallback(entry.name()))&&!name.equals(entry.name()))name=local.name();
            rows.putIfAbsent(id,new Choice(name,entry.size(),entry.hash(),local!=null&&!useServer?local.path():null,true,local!=null));
        }
        if(includeLocal)for(var local:locals){
            if(!local.name().toLowerCase(Locale.ROOT).contains(key)&&!local.path().getFileName().toString().toLowerCase(Locale.ROOT).contains(key))continue;
            rows.putIfAbsent(identity(local.hash(),local.size()),local);
        }
        return List.copyOf(rows.values());
    }
    private static String identity(String hash,int size){return hash+":"+size;}
    private ContentCardCatalog(){}
}
