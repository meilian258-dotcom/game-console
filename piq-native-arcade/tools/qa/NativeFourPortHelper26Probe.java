package cn.piq.nativearcade.bridge;

import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import com.sun.jna.Memory;

/** Executes real production helper command parser/callback, without loading the MAME DLL. */
public final class NativeFourPortHelper26Probe {
    private static int assertions;
    private static void check(boolean good,String message){assertions++;if(!good)throw new AssertionError(message);}
    private static NativeCoreWorker.Engine engine(){return new NativeCoreWorker.Engine(null,Path.of("unused-original-fixture.zip"),new DataOutputStream(new ByteArrayOutputStream()));}
    private static void command(DataOutputStream out,int p1,int p2,int p3,int p4)throws IOException{out.writeInt(BridgeProtocol.INPUT4);out.writeInt(p1);out.writeInt(p2);out.writeInt(p3);out.writeInt(p4);}
    private static void parse(NativeCoreWorker.Engine engine,byte[] bytes){InputStream old=System.in;try{System.setIn(new ByteArrayInputStream(bytes));engine.commands();}finally{System.setIn(old);}}
    public static void main(String[]args)throws Exception{
        if(args.length>0){
            check(Path.of(NativeCoreWorker.Engine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"actual Engine from supplied final helper JAR");
            check(Path.of(NativeProcessSession.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[1]).toRealPath()),"parent class from supplied final mod JAR");
        }
        check(BridgeProtocol.VERSION==3,"v3 numbered-button semantics");
        var all=engine();
        for(int port=0;port<4;port++)for(int bit=0;bit<16;bit++){
            int[] masks=new int[4];masks[port]=1<<bit;check(all.buttons.offer(masks[0],masks[1],masks[2],masks[3]),"queue bit");all.nextInputFrame();
            for(int p=0;p<4;p++)for(int b=0;b<16;b++)check(all.input.invoke(p,1,0,b)==(p==port&&b==(bit==1?8:bit==8?1:bit)?1:0),"actual helper input callback "+p+"/"+b);
            all.buttons.clear();all.nextInputFrame();
        }
        check(all.input.invoke(-1,1,0,0)==0&&all.input.invoke(4,1,0,0)==0,"invalid port");
        check(all.input.invoke(0,2,0,0)==0&&all.input.invoke(0,1,1,0)==0&&all.input.invoke(0,1,0,16)==0,"invalid input type");
        try(Memory users=new Memory(4)){check(all.environment.invoke(61,users)==1,"max-users accepted");check(users.getInt(0)==4,"reports four users");}
        for(int released=0;released<4;released++){
            var out=new ByteArrayOutputStream();var data=new DataOutputStream(out);command(data,1,2,4,8);command(data,16,32,64,128);command(data,0,0,0,0);data.writeInt(BridgeProtocol.RELEASE_PORT);data.writeInt(released);data.writeInt(BridgeProtocol.CLOSE);
            var e=engine();parse(e,out.toByteArray());check(e.failure==null,"actual command parser accepted");
            int[] a={1,2,4,8},b={16,32,64,128};a[released]=b[released]=0;e.nextInputFrame();check(Arrays.equals(a,e.current),"release removes old held+edge only this port");e.nextInputFrame();check(Arrays.equals(b,e.current),"other ports queued edge preserved");e.nextInputFrame();check(Arrays.equals(new int[4],e.current),"quick releases preserved");
        }
        var legacyOut=new ByteArrayOutputStream();var legacy=new DataOutputStream(legacyOut);legacy.writeInt(BridgeProtocol.INPUT);legacy.writeInt(3);legacy.writeInt(12);legacy.writeInt(BridgeProtocol.CLOSE);var old=engine();parse(old,legacyOut.toByteArray());old.nextInputFrame();check(Arrays.equals(new int[]{3,12,0,0},old.current),"old two-port command preserved");
        var malformed=new ByteArrayOutputStream();var bad=new DataOutputStream(malformed);bad.writeInt(BridgeProtocol.RELEASE_PORT);bad.writeInt(4);var rejected=engine();parse(rejected,malformed.toByteArray());check(rejected.failure!=null,"out-of-range release rejected");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_helper_parser_and_callbacks\":true,\"mame_dll_loaded\":false,\"minecraft_started\":false}");
    }
}

