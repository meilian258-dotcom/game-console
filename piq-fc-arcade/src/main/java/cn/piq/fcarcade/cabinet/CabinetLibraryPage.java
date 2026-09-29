package cn.piq.fcarcade.cabinet;

/** One shared page contract for the wire codec, server catalog and client navigation. */
public final class CabinetLibraryPage {
    public static final int SIZE=7, MAX_TOTAL=512;
    private CabinetLibraryPage(){}
    public static boolean validOffset(int offset){return offset>=0&&offset<=MAX_TOTAL&&offset%SIZE==0;}
    public static int offset(int requested,int total){
        if(!validOffset(requested)||total<0||total>MAX_TOTAL)throw new IllegalArgumentException("Invalid page bounds");
        return Math.min(requested,((Math.max(1,total)-1)/SIZE)*SIZE);
    }
    /** Includes the complete metadata/profile/header worst case; never the ROM contents. */
    public static int conservativeBytes(int entries){
        if(entries<0||entries>SIZE)throw new IllegalArgumentException("Invalid library page size");
        return 1024+entries*4096;
    }
}
