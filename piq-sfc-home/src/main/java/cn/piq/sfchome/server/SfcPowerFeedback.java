package cn.piq.sfchome.server;

/** Server-thread transition bookkeeping; pending preparation is not a power-on sound. */
final class SfcPowerFeedback {
    private boolean started,deferredStop,finished;
    boolean started(){if(started||finished)return false;started=true;return true;}
    void deferStop(){if(started&&!finished)deferredStop=true;}
    boolean finished(){if(finished)return false;finished=true;return deferredStop;}
}
