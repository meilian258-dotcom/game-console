package cn.piq.nativearcade.bridge;

import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;

/** Parses real parent FIFO bytes in the real v4 helper, without Engine.run, a ROM or a DLL. */
public final class NativeCoinHelper48Probe {
    private static int checks;
    private static void check(boolean good,String label){checks++;if(!good)throw new AssertionError(label);}
    private static void input(DataOutputStream out,int a,int b,int c,int d)throws IOException{out.writeInt(BridgeProtocol.INPUT4);out.writeInt(a);out.writeInt(b);out.writeInt(c);out.writeInt(d);}
    private static NativeCoreWorker.Engine parse(byte[] commands){
        var engine=new NativeCoreWorker.Engine(null,Path.of("unused-not-opened.zip"),new DataOutputStream(new ByteArrayOutputStream()));
        var original=System.in;try{System.setIn(new ByteArrayInputStream(commands));engine.commands();}finally{System.setIn(original);}
        return engine;
    }
    public static void main(String[] args)throws Exception {
        check(BridgeProtocol.VERSION==4,"v4 protocol");
        check(Path.of(NativeCoreWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"production helper JAR loaded");
        for(int port=0;port<4;port++) {
            var queue=new ArrayBlockingQueue<NativeProcessSession.Input>(136);
            for(int mask:new int[]{16,32,20,16,36,32}) {
                int[] values={1,2,8,16};values[port]=mask;
                queue.add(new NativeProcessSession.Input(BridgeProtocol.INPUT4,values[0],values[1],values[2],values[3],-1));
            }
            check(NativeProcessSession.projectPending(queue,port,true),"parent accepted soft release");
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(var message:queue) {
                if(message.command()==BridgeProtocol.INPUT4)input(out,message.p1(),message.p2(),message.p3(),message.p4());
                else {out.writeInt(message.command());out.writeInt(message.port());}
            }
            out.writeInt(BridgeProtocol.CLOSE);
            var engine=parse(bytes.toByteArray());check(engine.failure==null,"real parser accepted");
            for(int expected:new int[]{4,0,4,0,0}) {
                engine.nextInputFrame();check(engine.current[port]==expected,"coin edges remain exact");
                for(int bit=0;bit<16;bit++)check(engine.input.invoke(port,1,0,bit)==(bit==2&&expected==4?1:0),"real input callback cleared gameplay");
                for(int other=0;other<4;other++)if(other!=port)check(engine.current[other]==new int[]{1,2,8,16}[other],"other held port untouched");
            }
        }
        var full=new ByteArrayOutputStream();var out=new DataOutputStream(full);
        for(int n=0;n<126;n++)input(out,(n&1)==0?16:32,0,0,0);
        input(out,20,0,0,0);input(out,16,0,0,0);out.writeInt(BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN);out.writeInt(0);out.writeInt(BridgeProtocol.CLOSE);
        var compact=parse(full.toByteArray());check(compact.failure==null,"128 helper edges admitted");check(compact.buttons.pending(0)==2,"helper immediately compacts 128 old edges");
        compact.nextInputFrame();check(compact.current[0]==4,"paid down first next frame");compact.nextInputFrame();check(compact.current[0]==0,"paid up next frame");
        var malformed=new ByteArrayOutputStream();out=new DataOutputStream(malformed);out.writeInt(BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN);out.writeInt(4);
        check(parse(malformed.toByteArray()).failure!=null,"invalid port rejected");
        var truncated=new ByteArrayOutputStream();out=new DataOutputStream(truncated);out.writeInt(BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN);
        check(parse(truncated.toByteArray()).failure!=null,"truncated new command rejected");
        System.out.println("NATIVE_COIN_HELPER_V4_OK checks="+checks+" actual_helper_parser=true actual_parent_fifo=true dll_loaded=false rom_read=false");
    }
}
