package cn.piq.fcarcade.network;

import java.util.function.Supplier;

/** Client-thread lifecycle. A world/dimension or HUD change is not a network reconnection. */
public final class ModTrafficSession {
    private final Supplier<ModTrafficCounter> factory;
    private Object connection;
    private ModTrafficCounter counter;
    public ModTrafficSession(){this(ModTrafficCounter::new);}
    public ModTrafficSession(Supplier<ModTrafficCounter> factory){this.factory=java.util.Objects.requireNonNull(factory);}
    public ModTrafficCounter connect(Object next){
        if(connection!=next){connection=next;counter=next==null?null:factory.get();}
        return counter;
    }
}
