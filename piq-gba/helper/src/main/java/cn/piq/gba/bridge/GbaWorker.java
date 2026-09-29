// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Single-owner synchronous private protocol; launcher owns wall-clock timeout and process. */
public final class GbaWorker {
    public interface Kernel extends StdCallLibrary {
        Pointer GetCurrentProcess();Pointer GetStdHandle(int which);
        boolean DuplicateHandle(Pointer a,Pointer b,Pointer c,PointerByReference out,int access,boolean inherit,int options);
        boolean SetStdHandle(int which,Pointer handle);boolean CloseHandle(Pointer handle);
        boolean WriteFile(Pointer handle,byte[] data,int count,IntByReference written,Pointer overlapped);
    }
    public interface Crt extends Library {int fflush(Pointer stream);int _dup2(int source,int target);}
    private static final class Binary extends OutputStream {
        final Kernel kernel;final Pointer handle;
        Binary()throws IOException {kernel=Native.load("kernel32",Kernel.class);Pointer p=kernel.GetCurrentProcess();PointerByReference out=new PointerByReference();
            if(!kernel.DuplicateHandle(p,kernel.GetStdHandle(-11),p,out,0,false,2))throw new IOException("stdout duplication");handle=out.getValue();}
        @Override public void write(int b)throws IOException {write(new byte[]{(byte)b});}
        @Override public void write(byte[] bytes,int offset,int count)throws IOException {int done=0;while(done<count){byte[] slice=Arrays.copyOfRange(bytes,offset+done,offset+count);IntByReference written=new IntByReference();if(!kernel.WriteFile(handle,slice,slice.length,written,null)||written.getValue()<1)throw new IOException("pipe closed");done+=written.getValue();}}
        @Override public void close(){kernel.CloseHandle(handle);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("private DLL, ROM, directory");
        Binary binary=new Binary();System.setOut(new PrintStream(System.err,true,java.nio.charset.StandardCharsets.UTF_8));
        Crt crt=Native.load("msvcrt",Crt.class);crt.fflush(null);if(crt._dup2(2,1)!=0||!binary.kernel.SetStdHandle(-11,binary.kernel.GetStdHandle(-12)))throw new IOException("native stdout isolation");
        try(var in=new DataInputStream(new BufferedInputStream(System.in));var out=new DataOutputStream(new BufferedOutputStream(binary,65536));var core=new GbaCore(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]))){
            int initial=in.readInt();if(initial!=0&&!GbaProtocol.saveSize(initial))throw new IOException("Save input length");
            if(initial>0){byte[] save=in.readNBytes(initial);if(save.length!=initial)throw new EOFException();core.loadRam(save);}
            out.writeInt(GbaProtocol.MAGIC);out.writeInt(GbaProtocol.VERSION);out.writeDouble(core.fps());out.flush();
            int frames=0;
            for(;;){int command=in.readInt();
                if(command==GbaProtocol.CLOSE)return;
                if(command==GbaProtocol.STEP){int mask=in.readInt();var f=core.step(mask);if(f==null)throw new IOException("Missing frame");
                    out.writeInt(GbaProtocol.STEP);out.writeInt(f.width());out.writeInt(f.height());out.writeInt(f.pcm48k().length);
                    for(int pixel:f.abgr())out.writeInt(pixel);for(short s:f.pcm48k())out.writeShort(s);out.flush();
                    if(++frames>60*60*24*14)throw new IOException("Prototype lifetime limit");
                }else if(command==GbaProtocol.SAVE){byte[] save=core.saveRam();if(save.length!=0&&!GbaProtocol.saveSize(save.length))throw new IOException("Unexpected SaveRAM length");out.writeInt(GbaProtocol.SAVE);out.writeInt(save.length);out.write(save);out.flush();}
                else throw new IOException("Unknown private command");
            }
        }
    }
}
