// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.runtime;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
/** Package-private JNI. No arbitrary core/path is exposed to network or program selectors. */
final class PvzJniBridge {
    static final String SHA="C092822AED5CE937D141B2999715CF39EDBDA03D176B12D878A8D31CCC5D1E18";
    private static boolean loaded;
    static synchronized void load() throws Exception {
        if(loaded)return;
        Path dir=Files.createTempDirectory("piq-pvz-jni-bridge-");Path library=dir.resolve("piq-pvz-jni.dll");
        boolean success=false;
        try{
            try(var in=PvzJniBridge.class.getResourceAsStream("/core/pvz/piq-pvz-jni.dll")){
                if(in==null)throw new IOException("附属缺少JNI试验桥");Files.copy(in,library);
            }
            if(!PvzRuntime.sha(library).equals(SHA))throw new IOException("内置JNI桥校验失败");
            System.load(library.toAbsolutePath().toString());loaded=true;success=true;
            // Windows keeps a loaded DLL locked. It is one small bridge per JVM, not per game.
            dir.toFile().deleteOnExit();library.toFile().deleteOnExit();
        }finally{if(!success){Files.deleteIfExists(library);Files.deleteIfExists(dir);}}
    }
    static native long open(String core,String data,String saves) throws IOException;
    static native int step(long token,ByteBuffer video,ByteBuffer audio,int pad,int x,int y,int buttons,boolean pointer,int[] keys) throws IOException;
    static native void close(long token) throws IOException;
    static native String workingDirectory() throws IOException;
    private PvzJniBridge(){}
}
