package cn.piq.sfchome.server;

import java.nio.file.Path;
import java.util.*;

/** Final-JAR-only consent/bounded transfer gate; no Minecraft/core classes loaded. */
public final class Alpha18SfcJoinProbe {
    private static int checks;
    private static final UUID HOST=UUID.fromString("b668536c-86aa-4d31-a215-e1d8380f9a30"),GUEST=UUID.fromString("dfe499b5-d7aa-4e29-98c0-2622848b6222");
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void rejected(Runnable action,String label){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,label);}
    private static SfcJoinGate fresh(){return new SfcJoinGate(UUID.randomUUID(),HOST,GUEST,UUID.randomUUID(),UUID.randomUUID(),10);}
    private static SfcJoinGate capture(){var g=fresh();check(g.approve(HOST,g.token,true,11),"owner approves");check(g.capture(GUEST,127,12),"loaded applicant fixes next-frame boundary");return g;}
    private static Path origin(Class<?> cls)throws Exception{return Path.of(cls.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected exact final SFC JAR path");Path jar=Path.of(args[0]).toRealPath();
        check(origin(SfcJoinGate.class).equals(jar),"Gate must load from provided final JAR");check(origin(SfcJoinGate.Phase.class).equals(jar),"Phase must load from same final JAR");
        check(SfcJoinGate.MAX_STATE==16*1024*1024,"state cap fixed at 16 MiB");check(SfcJoinGate.CHUNK==30*1024,"30 KiB payload leaves outer packet header room");
        rejected(()->new SfcJoinGate(UUID.randomUUID(),HOST,HOST,UUID.randomUUID(),UUID.randomUUID(),0),"cannot invite yourself");
        rejected(()->new SfcJoinGate(UUID.randomUUID(),HOST,GUEST,UUID.randomUUID(),UUID.randomUUID(),-1),"negative clock");
        rejected(()->new SfcJoinGate(UUID.randomUUID(),HOST,GUEST,UUID.randomUUID(),UUID.randomUUID(),Long.MAX_VALUE),"clock overflow");
        for(int i=0;i<32;i++){
            var g=fresh();UUID stranger=UUID.randomUUID();
            check(g.phase()==SfcJoinGate.Phase.APPROVAL,"initial approval phase");check(!g.live(9),"pre-creation replay clock");
            check(!g.approve(stranger,g.token,true,11),"stranger cannot approve");check(!g.approve(GUEST,g.token,true,11),"applicant cannot approve");
            check(!g.approve(HOST,UUID.randomUUID(),true,11),"owner wrong nonce");check(!g.capture(GUEST,127,11),"cannot capture before approval");
            check(g.phase()==SfcJoinGate.Phase.APPROVAL,"failed operations preserve consent phase");check(g.approve(HOST,g.token,false,11),"explicit decline accepted");
            check(g.phase()==SfcJoinGate.Phase.CLOSED&&!g.live(12),"decline closes transaction");check(!g.approve(HOST,g.token,true,12),"declined consent cannot replay");
        }
        var timed=fresh();check(timed.approve(HOST,timed.token,true,11),"allow loading");check(timed.live(1500),"loading does not pause P1");check(!timed.live(2410),"full two-minute deadline");
        var shortPause=fresh();check(shortPause.approve(HOST,shortPause.token,true,11),"allow before pause");check(shortPause.capture(GUEST,200,1000),"later capture");
        check(shortPause.live(1599),"still within 30-second pause");check(!shortPause.live(1600),"pause ends at 600 ticks");
        var closeToEnd=fresh();check(closeToEnd.approve(HOST,closeToEnd.token,true,11),"allow near full deadline");check(closeToEnd.capture(GUEST,200,2400),"capture near full deadline");check(!closeToEnd.live(2410),"capture cannot extend whole lifetime");
        byte[] state=new byte[100003];new Random(18).nextBytes(state);byte[] original=state.clone();String hash=SfcJoinGate.sha(state);
        var g=capture();
        check(!g.append(GUEST,g.token,127,state.length,0,hash,new byte[]{1},13),"applicant cannot upload authoritative state");
        check(!g.append(HOST,UUID.randomUUID(),127,state.length,0,hash,new byte[]{1},13),"state nonce mismatch");
        check(!g.append(HOST,g.token,128,state.length,0,hash,new byte[]{1},13),"state frame mismatch");
        check(!g.append(HOST,g.token,127,0,0,hash,new byte[]{1},13),"empty state");
        check(!g.append(HOST,g.token,127,SfcJoinGate.MAX_STATE+1,0,hash,new byte[]{1},13),"allocation cap");
        check(!g.append(HOST,g.token,127,state.length,1,hash,new byte[]{1},13),"missing first chunk");
        check(!g.append(HOST,g.token,127,state.length,0,hash,new byte[0],13),"empty chunk");
        check(!g.append(HOST,g.token,127,state.length,0,hash,new byte[SfcJoinGate.CHUNK+1],13),"chunk cap");
        check(!g.append(HOST,g.token,127,state.length,0,"bad",new byte[]{1},13),"invalid hash");
        check(!g.commit(GUEST,g.token,127,hash,13),"no early commit");check(g.bytes()==null,"no ready state exposed yet");
        for(int at=0;at<state.length;at+=SfcJoinGate.CHUNK){int end=Math.min(state.length,at+SfcJoinGate.CHUNK);byte[] part=Arrays.copyOfRange(state,at,end);
            check(g.append(HOST,g.token,127,state.length,at,hash,part,13),"ordered chunk accepted");part[0]^=1;
            if(end<state.length){check(!g.append(HOST,g.token,127,state.length,at,hash,part,13),"duplicate chunk rejected");check(!g.append(HOST,g.token,127,state.length+1,end,hash,new byte[]{1},13),"total cannot change");check(!g.append(HOST,g.token,127,state.length,end,"b".repeat(64),new byte[]{1},13),"hash cannot change");}
        }
        check(Arrays.equals(state,original),"transaction never mutates source P1 snapshot");check(g.phase()==SfcJoinGate.Phase.APPLYING,"complete verified state awaits P2");check(Arrays.equals(g.bytes(),original),"each inbound chunk copied before caller mutation");
        check(!g.commit(HOST,g.token,127,hash,14),"P1 cannot pretend to be P2 import ack");check(!g.commit(GUEST,UUID.randomUUID(),127,hash,14),"ack nonce");check(!g.commit(GUEST,g.token,128,hash,14),"ack frame");check(!g.commit(GUEST,g.token,127,"c".repeat(64),14),"ack digest");
        check(g.commit(GUEST,g.token,127,hash,14),"single atomic P2 import ack");check(g.phase()==SfcJoinGate.Phase.COMMITTED,"committed phase");check(g.bytes()==null&&!g.live(15),"committed buffer and reservation released");check(!g.commit(GUEST,g.token,127,hash,15),"ack replay");
        var corrupt=capture();check(!corrupt.append(HOST,corrupt.token,127,3,0,"a".repeat(64),new byte[]{1,2,3},13),"bad final digest rejected");check(corrupt.phase()==SfcJoinGate.Phase.CLOSED&&corrupt.bytes()==null,"bad digest discards buffer");
        var canceled=capture();check(canceled.append(HOST,canceled.token,127,state.length,0,hash,Arrays.copyOf(state,SfcJoinGate.CHUNK),13),"partial transfer");canceled.close();check(canceled.bytes()==null&&!canceled.live(14),"cancel discards partial buffer");check(Arrays.equals(state,original),"cancel preserves original P1 snapshot bytes");
        String path=jar.toString().replace("\\","\\\\").replace("\"","\\\"");
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"production_origin\":\"final-jar-only\",\"production_jar\":\""+path+"\",\"minecraft_or_native_core_started\":false}");
    }
}
