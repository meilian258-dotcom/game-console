package cn.piq.fcarcade.client;

/** Per-tab catalog state. Scans and their durable diagnostics do not own mutation state. */
final class ContentCardCatalogState {
    private static final long REFRESH_NANOS=1_000_000_000L,TIMEOUT_NANOS=120_000_000_000L;
    private boolean serverPending,localPending,requested,localRequested,serverExpired;
    private long requestedAt,localRequestedAt;
    private String server="服务器：等待读取",local="本地：等待读取",serverDetails="",localDetails="",hint="";
    boolean serverPending(){return serverPending;}
    boolean serverExpired(){return serverExpired;}
    boolean allowsServerMutation(){return !serverPending&&!serverExpired;}
    boolean localPending(){return localPending;}
    boolean beginServer(long now){
        if(serverExpired)return false;
        if(serverPending||requested&&now-requestedAt<REFRESH_NANOS){hint="刷新过快，请稍后再试；上次扫描结果保留";return false;}
        requested=true;requestedAt=now;serverPending=true;hint="";return true;
    }
    void serverSuccess(String summary,String details){if(serverExpired)return;serverPending=false;server=summary;serverDetails=details;hint="";}
    void serverFailure(String reason){
        if(serverExpired)return;
        serverPending=false;
        if(reason.contains("刷新过快")){hint=reason;return;}
        server="服务器读取失败："+reason;serverDetails=reason;hint="";
    }
    boolean expireServer(long now){
        if(!serverPending||now-requestedAt<=TIMEOUT_NANOS)return false;
        serverFailure("服务器目录请求超时，请关闭后重新打开；本地列表保留，本次会话不再上传或写卡");
        serverExpired=true;return true;
    }
    boolean beginLocal(long now){
        if(localPending||localRequested&&now-localRequestedAt<REFRESH_NANOS){hint="刷新过快，请稍后再试；上次扫描结果保留";return false;}
        localRequested=true;localRequestedAt=now;localPending=true;return true;
    }
    void localSuccess(String summary,String details){localPending=false;local=summary;localDetails=details;}
    void localFailure(String reason){localPending=false;local="本地读取失败："+reason;localDetails=reason;}
    String summary(){return (serverPending?"服务器扫描中（上次结果保留）":server)+"；"+(localPending?"本地扫描中（上次结果保留）":local)+(hint.isEmpty()?"":"；"+hint);}
    String details(){return summary()+"\n"+serverDetails+"\n"+localDetails+(hint.isEmpty()?"":"\n"+hint);}
    boolean hasFailure(){return !serverDetails.isBlank()||!localDetails.isBlank();}
}
