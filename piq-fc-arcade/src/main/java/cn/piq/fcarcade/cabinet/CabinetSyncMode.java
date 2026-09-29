package cn.piq.fcarcade.cabinet;

/** Configuration is captured at room creation, never changed in a running room. */
public enum CabinetSyncMode {
    MEDIA, LOCAL_SYNC, SERVER_MEDIA;
    public static CabinetSyncMode checked(int id){
        if(id<0||id>=values().length)throw new IllegalArgumentException("Invalid synchronization mode");
        return values()[id];
    }
}
