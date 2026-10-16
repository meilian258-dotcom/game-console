// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.watch;

import java.util.Collection;

/** First admitted source wins. A rejected offer does not prepare content or evict another source. */
final class WatchAdmissionPolicy {
    private WatchAdmissionPolicy(){}
    /** Null jni preserves legacy providers whose execution mode is not known until content is loaded. */
    record Claim(boolean netplay,Boolean jni) {
        boolean singleSource(){return !netplay||Boolean.FALSE.equals(jni);}
    }
    static boolean allows(Claim incoming,Collection<Claim> active,int capacity,boolean operating,boolean processClosing){
        if(incoming.singleSource()&&(operating||!active.isEmpty()))return false;
        if(!incoming.netplay())return true;
        if(processClosing||active.size()>=Math.max(0,Math.min(4,capacity)))return false;
        return active.stream().noneMatch(Claim::singleSource);
    }
}
