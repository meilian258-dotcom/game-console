package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.content.ContentCardNetwork;
import java.util.*;

/** Bounded read-only requests. Runtime ownership and server capabilities remain separate. */
final class ContentCardDownloads {
    static final int LIMIT=4;
    private final Map<UUID,ContentCardDownloadRequest> pending=new LinkedHashMap<>();
    synchronized void add(ContentCardDownloadRequest request){
        if(pending.containsKey(request.token))throw new IllegalStateException("Duplicate content request token");
        if(pending.size()>=LIMIT)throw new IllegalStateException("Read-only content downloads busy");
        pending.put(request.token,request);
    }
    synchronized ContentCardDownloadRequest get(UUID token){return pending.get(token);}
    synchronized ContentCardDownloadRequest find(ContentCardNetwork.Message message,Object connection){
        var request=pending.get(message.token());
        return request!=null&&request.connection==connection&&request.matches(message)?request:null;
    }
    synchronized boolean contains(ContentCardDownloadRequest request){return pending.get(request.token)==request;}
    synchronized boolean remove(ContentCardDownloadRequest request){return pending.remove(request.token,request);}
    synchronized List<ContentCardDownloadRequest> snapshot(){return List.copyOf(pending.values());}
}
