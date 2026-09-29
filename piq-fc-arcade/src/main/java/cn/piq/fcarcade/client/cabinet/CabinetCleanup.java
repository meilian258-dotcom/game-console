package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.retro.api.RetroEmulator;

/** One broken addon callback must not prevent native shutdown or the host releasing its lease/UI. */
final class CabinetCleanup {
    private CabinetCleanup(){}
    static void close(CabinetEmulator emulator){
        if(emulator==null)return;
        try{emulator.clearInput();}catch(RuntimeException|LinkageError ignored){}
        try{emulator.close();}catch(RuntimeException|LinkageError ignored){}
    }
    /** Different name preserves unambiguous source/binary compatibility for close(null). */
    static void closeRetro(RetroEmulator emulator){
        if(emulator==null)return;
        try{emulator.clearInput();}catch(RuntimeException|LinkageError ignored){}
        try{emulator.close();}catch(RuntimeException|LinkageError ignored){}
    }
}
