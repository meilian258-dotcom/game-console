package cn.piq.replayquick;

import io.netty.buffer.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercises the compiled production completion method without starting Minecraft. */
public final class QuickSableStateBehaviorTest {
    private static int checks;
    public static QuickSableState state(SableFlagTimeline flags,int teleportId)throws Exception {
        return state(flags,teleportId,(pipeline,x,y,z)->false);
    }
    static QuickSableState state(SableFlagTimeline flags,int teleportId,QuickSableState.PositionProof proof)throws Exception {
        var state=new QuickSableState(null,null,proof);
        var field=QuickSableState.class.getDeclaredField("flags");field.setAccessible(true);field.set(state,flags);
        field=QuickSableState.class.getDeclaredField("teleportId");field.setAccessible(true);field.setInt(state,teleportId);
        return state;
    }
    public static boolean complete(QuickSableState state,ByteBuf bytes,int target)throws Throwable {
        var method=QuickSableState.class.getDeclaredMethod("complete",ByteBuf.class,int.class);method.setAccessible(true);
        try{return (boolean)method.invoke(state,bytes,target);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    public static byte[] contents(ByteBuf bytes){byte[] value=new byte[bytes.readableBytes()];bytes.getBytes(bytes.readerIndex(),value);return value;}
    static void require(boolean valid,String why){checks++;if(!valid)throw new AssertionError(why);}
    static void varint(ByteBuf bytes,int value){while((value&~127)!=0){bytes.writeByte((value&127)|128);value>>>=7;}bytes.writeByte(value);}
    static ByteBuf packet(int packetId,int entity,int body){var bytes=Unpooled.buffer();bytes.writeByte(99);varint(bytes,packetId);varint(bytes,entity);bytes.writeZero(body);bytes.readerIndex(1);return bytes;}
    static void accepted(QuickSableState state,ByteBuf bytes,int target,boolean change,Boolean flag)throws Throwable {
        try{int start=bytes.readerIndex(),writer=bytes.writerIndex(),refs=bytes.refCnt();byte[] before=contents(bytes);
            require(complete(state,bytes,target)==change,"completion outcome");require(bytes.readerIndex()==start,"readerIndex preserved");
            require(bytes.refCnt()==refs,"refcount unchanged");require(bytes.writerIndex()==writer+(change?1:0),"only one byte appended");
            require(Arrays.equals(before,Arrays.copyOf(contents(bytes),before.length)),"original prefix unchanged");
            if(flag!=null)require(bytes.getBoolean(bytes.writerIndex()-1)==flag,"correct original state");
            require(!complete(state,bytes,target),"repeated completion is idempotent");require(bytes.writerIndex()==writer+(change?1:0),"no duplicate tail");
        }finally{require(bytes.release(),"caller retains ownership");}
    }
    static void rejected(QuickSableState state,ByteBuf bytes,int target)throws Throwable {
        try{int start=bytes.readerIndex(),writer=bytes.writerIndex(),refs=bytes.refCnt();byte[] before=contents(bytes);
            try{complete(state,bytes,target);throw new AssertionError("Expected MissingFlag");}
            catch(RuntimeException expected){require(expected.getClass().getName().equals("cn.piq.replayquick.QuickSableState$MissingFlag"),"typed production failure");}
            require(bytes.readerIndex()==start&&bytes.writerIndex()==writer,"indices unchanged on failure");
            require(bytes.refCnt()==refs&&Arrays.equals(before,contents(bytes)),"ownership and bytes unchanged on failure");
        }finally{require(bytes.release(),"caller can release failed input");}
    }
    public static void main(String[] args){try{run();System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
    static void run()throws Throwable {
        var builder=new SableFlagTimeline.Builder();builder.spawn(0,7,true);builder.observe(1,7,SableFlagTimeline.Flag.FALSE);
        builder.observe(10,7,SableFlagTimeline.Flag.TRUE);builder.observe(20,7,SableFlagTimeline.Flag.UNKNOWN);builder.spawn(21,8,false);
        var state=state(builder.build(),112);
        accepted(state,packet(112,7,27),5,true,false);accepted(state,packet(112,7,27),15,true,true);
        accepted(state,packet(112,8,27),25,false,null);accepted(state,packet(111,7,27),15,false,null);
        var existing=packet(112,7,28);existing.setBoolean(existing.writerIndex()-1,true);accepted(state,existing,5,false,true);
        rejected(state,packet(112,7,27),0);rejected(state,packet(112,7,27),20);
        rejected(state,packet(112,7,26),5);rejected(state,packet(112,7,29),5);
        rejected(state,Unpooled.buffer().writeByte(128),5);
        rejected(state,Unpooled.buffer().writeBytes(new byte[]{(byte)128,(byte)128,(byte)128,(byte)128,16}),5);
        rejected(state,Unpooled.buffer().writeBytes(new byte[]{112,(byte)128}),5);
        rejected(state,Unpooled.buffer().writeBytes(new byte[]{112,(byte)128,(byte)128,(byte)128,(byte)128,16}),5);
        var identityScope=packet(112,7,27);identityScope.retain();
        try{int refs=identityScope.refCnt();require(complete(state,identityScope,5),"shared-reference completion");require(identityScope.refCnt()==refs,"does not claim ownership");}
        finally{require(!identityScope.release(),"first retained release");require(identityScope.release(),"second retained release");}
        worldSpaceProof();
        System.out.println("QuickSableStateBehaviorTest checks="+checks+" PASS (production method, no game started)");
    }

    static ByteBuf positioned(int entity,double x,double y,double z) {
        var bytes=packet(112,entity,27);int body=bytes.writerIndex()-27;
        bytes.setDouble(body,x);bytes.setDouble(body+8,y);bytes.setDouble(body+16,z);return bytes;
    }
    static void worldSpaceProof()throws Throwable {
        var builder=new SableFlagTimeline.Builder();builder.spawn(0,7,true);
        builder.observe(0,7,SableFlagTimeline.Flag.FALSE);
        builder.observe(0,7,SableFlagTimeline.Flag.UNKNOWN); // Trimmed startup: original teleport then unmarked deltas.
        builder.observe(100,7,SableFlagTimeline.Flag.TRUE);
        builder.observe(200,7,SableFlagTimeline.Flag.UNKNOWN);
        builder.despawn(300,7);builder.spawn(301,7,false);builder.worldReset(400);builder.spawn(401,7,true);
        var index=builder.build();int[] probes={0};boolean[] outside={true};
        var tested=state(index,112,(pipeline,x,y,z)->{probes[0]++;require(x==12.5&&y==70.0&&z==-24.25,"exact generated coordinates, not previous state");return outside[0];});
        accepted(tested,positioned(7,12.5,70,-24.25),0,true,false);
        require(index.flagAt(7,0)==SableFlagTimeline.Flag.UNKNOWN,"proof does not rewrite unknown recorded history");
        require(probes[0]==1,"proof only on first completion, no duplicate append");
        outside[0]=false;rejected(tested,positioned(7,12.5,70,-24.25),0);
        require(probes[0]==2,"bounds checked afresh even same target");
        accepted(tested,positioned(7,12.5,70,-24.25),100,true,true);
        require(probes[0]==2,"known true never replaced by world proof");
        rejected(tested,positioned(7,12.5,70,-24.25),200);
        accepted(tested,positioned(7,12.5,70,-24.25),301,false,null);
        require(probes[0]==3,"not consulted for reused non-player ID");
        outside[0]=true;accepted(tested,positioned(7,12.5,70,-24.25),401,true,false);
        require(index.flagAt(7,401)==SableFlagTimeline.Flag.UNKNOWN,"world reset does not borrow past flag");
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            rejected(tested,positioned(7,invalid,70,-24.25),0);
            rejected(tested,positioned(7,12.5,invalid,-24.25),0);
            rejected(tested,positioned(7,12.5,70,invalid),0);
        }
        require(probes[0]==4,"invalid coordinates rejected before proof");
        accepted(tested,packet(112,7,28),0,false,null);rejected(tested,packet(112,7,26),0);
        require(probes[0]==4,"already marked/invalid layouts never invoke proof");
    }
}
