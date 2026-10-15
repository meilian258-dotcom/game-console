// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.cabinet;

import cn.piq.retro.input.InputOwnership;

/** Shared local keyboard ownership across generic and legacy addon cabinets. */
public final class CabinetClientOwner {
    private CabinetClientOwner(){}
    public static synchronized boolean acquire(Object candidate){
        return InputOwnership.acquire(candidate);
    }
    public static synchronized void release(Object candidate){InputOwnership.release(candidate);}
}
