package cn.piq.nativearcade.bridge;

import java.io.*;
import java.nio.file.Path;

/** QA child only. Never contains or loads a native core; not a distributable helper. */
public final class NativeStepWorker {
    public static void main(String[] args)throws Exception{
        String mode=Path.of(args[1]).getFileName().toString().replace(".zip","");
        DataInputStream in=new DataInputStream(new BufferedInputStream(System.in));
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(System.out));
        if(mode.equals("nohello")){System.err.println("QA_NO_HELLO");forever();return;}
        NativeStepProtocol.writeHello(out,new NativeStepProtocol.Hello(60,48000,4));out.flush();
        long expected=1,frame=0;
        for(;;){
            var command=NativeStepProtocol.readRequest(in,expected++);
            if(mode.equals("blocked")){System.err.println("QA_STEP_BLOCKED");forever();return;}
            if(mode.equals("eof"))return;
            if(mode.equals("badid")){out.writeInt(NativeStepProtocol.FRAME);out.writeLong(command.id()+1);out.flush();forever();return;}
            if(mode.equals("badframe")){
                out.writeInt(NativeStepProtocol.FRAME);out.writeLong(command.id());out.writeLong(1);
                out.writeDouble(60);out.writeDouble(48000);out.writeBoolean(true);out.writeBoolean(true);
                out.writeInt(NativeStepProtocol.MAX_DIM+1);out.writeInt(1);out.writeFloat(4f/3);out.writeInt(0);out.writeInt(0);
                out.flush();forever();return;
            }
            NativeStepProtocol.Reply reply=switch(command.kind()){
                case NativeStepProtocol.STEP->new NativeStepProtocol.Frame(command.id(),++frame,60,48000,true,true,1,1,4f/3,0,
                    new int[]{0xff000000|(command.p1()^command.p2()^command.p3()^command.p4())},new short[]{(short)frame,(short)-frame});
                case NativeStepProtocol.SAVE->new NativeStepProtocol.State(command.id(),frame,new byte[]{(byte)frame,42});
                case NativeStepProtocol.LOAD->{frame=command.frame();yield new NativeStepProtocol.Loaded(command.id(),frame);}
                case NativeStepProtocol.CLOSE->new NativeStepProtocol.Closed(command.id());
                default->throw new AssertionError();
            };
            NativeStepProtocol.writeReply(out,reply);out.flush();
            if(command.kind()==NativeStepProtocol.CLOSE)return;
        }
    }
    private static void forever()throws InterruptedException{for(;;)Thread.sleep(60000);}
}
