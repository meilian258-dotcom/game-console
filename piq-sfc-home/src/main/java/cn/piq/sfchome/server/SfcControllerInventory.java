package cn.piq.sfchome.server;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.Consumer;

/** Personal slot aliases are harmless; two distinct copies of one lease are never ownership. */
public final class SfcControllerInventory {
    private SfcControllerInventory() {}
    /** Q removes its source before the toss callback, while an outside-GUI
     * click keeps the exact tossed cursor until the callback returns.
     * A remaining distinct copy must never be able to return another receipt.
     */
    public static <T> boolean removedForToss(UUID lease,Iterable<T> entries,T cursor,T dropped,
                                            Function<T,UUID> identity,ToIntFunction<T> count){
        if(lease==null||dropped==null||count.applyAsInt(dropped)!=1||!lease.equals(identity.apply(dropped)))return false;
        Set<T> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(T entry:entries){
            if(entry==null||!seen.add(entry)||count.applyAsInt(entry)<=0||!lease.equals(identity.apply(entry)))continue;
            if(entry!=cursor||cursor!=dropped||count.applyAsInt(entry)!=1)return false;
        }
        return true;
    }
    /** Revoke every distinct current copy, including a cursor copy replacing the original stack. */
    public static <T> int revoke(UUID lease,Iterable<T> entries,Function<T,UUID> identity,Consumer<T> remove){
        if(lease==null)return 0;
        Set<T> seen=Collections.newSetFromMap(new IdentityHashMap<>());int removed=0;
        for(T entry:entries)if(entry!=null&&seen.add(entry)&&lease.equals(identity.apply(entry))){remove.accept(entry);removed++;}
        return removed;
    }
    public static <T> T unique(UUID lease,Iterable<T> entries,Function<T,UUID> identity,ToIntFunction<T> count){
        if(lease==null)return null;
        Set<T> seen=Collections.newSetFromMap(new IdentityHashMap<>());T found=null;
        for(T entry:entries){
            if(entry==null||!seen.add(entry)||!lease.equals(identity.apply(entry)))continue;
            if(count.applyAsInt(entry)!=1||found!=null)return null;
            found=entry;
        }
        return found;
    }
}
