package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetEmulator;

/** Nonblocking server-side handle. close is a request; only isTerminated releases capacity. */
public interface ServerCoreHandle extends CabinetEmulator {
    boolean isTerminated();
    default boolean supportsReset(){return false;}
    default void reset(){throw new UnsupportedOperationException("This hosted core cannot reset; stop and restart it");}
    default boolean supportsZapper(){return false;}
    default void offerZapper(int packed){throw new UnsupportedOperationException("This hosted core has no light gun");}
    default void offerFrameInput(int a,int b,int c,int d,int packed){offerInputs(a,b,c,d);if(supportsZapper())offerZapper(packed);else if(packed!=cn.piq.fcarcade.session.ZapperInput.NEUTRAL)throw new UnsupportedOperationException("Light gun unavailable");}
}
