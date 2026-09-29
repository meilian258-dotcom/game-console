package cn.piq.fcarcade.home;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;

/** Isolate provider failures and same-object recursion without retaining unloaded worlds. */
final class HomeSystemCalls {
    private final ThreadLocal<Set<Object>> active = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    boolean invoke(Object owner, Runnable callback, Consumer<RuntimeException> failure) {
        Set<Object> owners = active.get();
        if (!owners.add(owner)) return false;
        try { callback.run(); return true; }
        catch (RuntimeException error) { failure.accept(error); return false; }
        finally { owners.remove(owner); if (owners.isEmpty()) active.remove(); }
    }
}
