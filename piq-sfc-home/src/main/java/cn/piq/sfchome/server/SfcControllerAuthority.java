package cn.piq.sfchome.server;

import java.util.Objects;
import java.util.UUID;

/** Immutable server-owned identity; replacing a P2 lease never authorizes old control messages. */
public final class SfcControllerAuthority {
    public static final double MAX_CABLE_DISTANCE=6.0;
    /** Feet-to-console-block-center distance, inclusive at exactly six blocks. */
    public static boolean withinCableDistance(double squaredDistance){
        return Double.isFinite(squaredDistance)&&squaredDistance>=0&&squaredDistance<=MAX_CABLE_DISTANCE*MAX_CABLE_DISTANCE;
    }
    private final UUID lease,player;
    private final int port;
    public SfcControllerAuthority(UUID lease,UUID player,int port){
        this.lease=Objects.requireNonNull(lease);this.player=Objects.requireNonNull(player);
        if(port<0||port>1)throw new IllegalArgumentException("Invalid controller port");this.port=port;
    }
    public boolean accepts(UUID actor,UUID presentedLease){return player.equals(actor)&&lease.equals(presentedLease);}
    public boolean itemMatches(boolean controller,UUID itemLease,int itemPort,int count){
        return controller&&lease.equals(itemLease)&&port==itemPort&&count==1;
    }
    public boolean inventoryMatches(int identityCopies,boolean inEitherHand,boolean requireHeld){
        return identityCopies==1&&(!requireHeld||inEitherHand);
    }
    public record Input(int mask,boolean release) {}
    public static Input input(boolean held,int mask,boolean release){
        if(mask<0||mask>4095||release&&mask!=0)return null;
        return new Input(held?mask:0,release||!held);
    }
}
