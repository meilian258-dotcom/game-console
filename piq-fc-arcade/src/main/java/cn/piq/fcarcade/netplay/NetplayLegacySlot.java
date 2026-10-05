// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Select an existing single-checkpoint layout without migration or premature writes.
 * All methods are IO-worker only; the server supplies the directory and exact identity. */
public final class NetplayLegacySlot {
    public static String version(byte[] bytes){return bytes==null?"":NetplaySaveState.hash(bytes);}
    public static String inspect(Path path,NetplaySaveState.Identity identity)throws IOException{
        return version(NetplaySaveStore.readOnly(path,identity));
    }
    public static NetplaySaveServer.Storage lease(Path path,NetplaySaveState.Identity identity,String expected,boolean resume)throws IOException{
        var store=new NetplaySaveStore(path,identity);
        try{
            String actual=version(store.read());
            if(!Objects.equals(expected,actual)||resume&&actual.isEmpty())throw new IOException("存档已变化，请重新开机选择");
            return new NetplaySaveServer.Storage(){
                public byte[] read()throws IOException{return resume?store.read():null;}
                public void write(byte[] bytes)throws IOException{store.write(bytes);}
                public void close()throws IOException{store.close();}
            };
        }catch(IOException|RuntimeException failure){store.close();throw failure;}
    }
    private NetplayLegacySlot(){}
}
