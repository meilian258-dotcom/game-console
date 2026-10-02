// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.save;

import cn.piq.fcarcade.netplay.*;
import cn.piq.mdhome.client.MdProfile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Three MD-wide personal slots or one physical card progress. All methods run on bounded IO. */
public final class MdSaveCatalog {
    public static final int SLOTS=3,MAX_CHECKPOINT=8*1024*1024,MAX_ROWS=64,MAX_SCAN=256;
    private static final int MAGIC=0x4d445331;
    private final Path root;
    public MdSaveCatalog(Path root){this.root=root.toAbsolutePath().normalize();}
    public record Row(String owner,String id,String version,String name,int players,
                      NetplaySaveState.Identity identity,long frame,long modified,long bytes){}
    public record Listing(List<Row> rows,boolean truncated){public Listing{rows=List.copyOf(rows);}}
    private record Record(Row row,byte[] checkpoint){}
    public static String personal(UUID player,int slot){
        if(player==null||slot<1||slot>SLOTS)throw new IllegalArgumentException("个人槽编号无效");
        return "personal|"+player+"|slot|"+slot;
    }
    public static String cartridge(UUID card){if(card==null||card.equals(new UUID(0,0)))throw new IllegalArgumentException("卡带编号无效");return "card|"+card;}
    public static boolean owned(String owner,UUID player,UUID card){
        for(int slot=1;slot<=SLOTS;slot++)if(personal(player,slot).equals(owner))return true;
        return card!=null&&cartridge(card).equals(owner);
    }
    public static boolean card(String owner){return owner.startsWith("card|");}
    public static NetplaySaveState.Identity identity(String rom){
        String descriptor="game-console-md-public-v1\nJNI\n"+MdProfile.SHA+"\n"+MdProfile.profile().devices()+"\n"+new TreeMap<>(MdProfile.profile().options());
        return new NetplaySaveState.Identity(hash(descriptor),rom);
    }
    private static String hash(String text){return NetplaySaveState.hash(text.getBytes(StandardCharsets.UTF_8));}
    public static String id(String owner){validateOwner(owner);return hash(owner);}
    private static void validateOwner(String owner){
        if(owner==null)throw new IllegalArgumentException("存档归属无效");
        var parts=owner.split("\\|",-1);
        try{UUID uuid=UUID.fromString(parts[1]);if(!uuid.toString().equals(parts[1])||uuid.equals(new UUID(0,0)))throw new IllegalArgumentException();
            if(parts.length==2&&parts[0].equals("card"))return;
            if(parts.length==4&&parts[0].equals("personal")&&parts[2].equals("slot")&&parts[3].matches("[1-3]"))return;
        }catch(RuntimeException ignored){}
        throw new IllegalArgumentException("存档归属无效");
    }
    private static NetplaySaveState.Identity outer(String owner){return new NetplaySaveState.Identity(hash("game-console-md-slot-envelope-v1"),id(owner));}
    private Path directory(String owner){return root.resolve(id(owner));}
    public String lockKey(String owner){validateOwner(owner);String authority=card(owner)?owner:"personal|"+owner.split("\\|")[1];return root+"|md-public|"+hash(authority);}
    public static String name(String name){if(name==null||name.isBlank()||name.length()>32||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("存档名称需要1～32字");return name.strip();}
    public Row read(String owner)throws IOException{
        byte[] bytes=NetplaySaveStore.readOnly(directory(owner),outer(owner));
        return bytes==null?null:decode(owner,bytes,modified(owner)).row();
    }
    private long modified(String owner)throws IOException{return Math.max(0,Files.getLastModifiedTime(directory(owner).resolve("checkpoint.bin"),LinkOption.NOFOLLOW_LINKS).toMillis());}
    public Listing list(String rom,UUID player,UUID card,boolean op)throws IOException{
        identity(rom);var rows=new ArrayList<Row>();boolean truncated=false;
        var owners=new LinkedHashSet<String>();for(int slot=1;slot<=SLOTS;slot++)owners.add(personal(player,slot));if(card!=null)owners.add(cartridge(card));
        for(String owner:owners){var row=read(owner);if(row!=null&&row.identity().content().equals(rom))rows.add(row);}
        if(op&&Files.exists(root,LinkOption.NOFOLLOW_LINKS)){
            NetplaySaveStore.safeDirectory(root);int count=0;
            try(var dirs=Files.newDirectoryStream(root)){
                for(Path dir:dirs){if(++count>MAX_SCAN){truncated=true;break;}
                    String folder=dir.getFileName().toString();if(!folder.matches("[0-9a-f]{64}")||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))continue;
                    try{byte[] bytes=NetplaySaveStore.readOnly(dir,new NetplaySaveState.Identity(hash("game-console-md-slot-envelope-v1"),folder));if(bytes==null)continue;
                        var record=decode(null,bytes,Math.max(0,Files.getLastModifiedTime(dir.resolve("checkpoint.bin"),LinkOption.NOFOLLOW_LINKS).toMillis()));
                        if(!folder.equals(record.row.id()))throw new IOException("存档路径与归属不符");
                        if(record.row.identity().content().equals(rom)&&!owners.contains(record.row.owner())){rows.add(record.row);if(rows.size()>=MAX_ROWS){truncated=true;break;}}
                    }catch(IOException|IllegalArgumentException invalid){truncated=true;}
                }
            }
        }
        rows.sort(Comparator.comparingLong(Row::modified).reversed());return new Listing(rows,truncated);
    }
    public Lease lease(String owner,NetplaySaveState.Identity expected,String version,String name,int players,boolean resume)throws IOException{
        return new Lease(owner,expected,version,name,players,resume);
    }
    public final class Lease implements NetplaySaveServer.Storage {
        private final String owner,saveName;private final int players;private final NetplaySaveState.Identity expected;
        private final boolean resume;private final NetplaySaveStore store;private boolean closed;
        private Lease(String owner,NetplaySaveState.Identity expected,String version,String name,int players,boolean resume)throws IOException{
            validateOwner(owner);this.owner=owner;this.expected=Objects.requireNonNull(expected);this.saveName=name(name);this.players=players;this.resume=resume;
            if(players<1||players>2)throw new IllegalArgumentException("存档人数无效");
            store=new NetplaySaveStore(directory(owner),outer(owner));
            try{byte[] old=store.read();var record=old==null?null:decode(owner,old,0);
                if(!Objects.equals(version,record==null?"":record.row.version()))throw new IOException("存档已变化，请重新选择");
                if(resume&&(record==null||!record.row.identity().equals(expected)))throw new IOException("存档属于另一游戏或不兼容核心，原档保留");
            }catch(IOException|RuntimeException failure){store.close();throw failure;}
        }
        public synchronized byte[] read()throws IOException{if(closed)throw new IOException("存档已关闭");byte[] bytes=store.read();if(bytes==null||!resume)return null;var record=decode(owner,bytes,0);if(!record.row.identity().equals(expected))throw new IOException("存档身份不兼容");return record.checkpoint.clone();}
        public synchronized void write(byte[] bytes)throws IOException{if(closed)throw new IOException("存档已关闭");var state=checkpoint(bytes,expected);store.write(encode(owner,saveName,players,bytes,state.frame()));}
        public synchronized void close()throws IOException{if(!closed){closed=true;store.close();}}
    }
    public void rename(Row expected,String name)throws IOException{rename(expected,name,()->true);}
    public void rename(Row expected,String name,BooleanSupplier authorized)throws IOException{
        name=name(name);try(var store=new NetplaySaveStore(directory(expected.owner()),outer(expected.owner()))){
            byte[] bytes=store.read();var old=bytes==null?null:decode(expected.owner(),bytes,0);version(old,expected.version());
            if(!authorized.getAsBoolean())throw new IOException("存档管理授权已撤销");
            store.write(encode(expected.owner(),name,old.row.players(),old.checkpoint,old.row.frame()));
        }
    }
    public void delete(Row expected)throws IOException{delete(expected,()->true);}
    public void delete(Row expected,BooleanSupplier authorized)throws IOException{
        try(var store=new NetplaySaveStore(directory(expected.owner()),outer(expected.owner()))){
            byte[] bytes=store.read();var old=bytes==null?null:decode(expected.owner(),bytes,0);version(old,expected.version());
            Path deleted=directory(expected.owner()).resolve("deleted");NetplaySaveStore.safeDirectory(deleted);
            if(!authorized.getAsBoolean())throw new IOException("存档管理授权已撤销");
            // The valid old envelope remains recoverable; previous checkpoints are never removed.
            Files.move(directory(expected.owner()).resolve("checkpoint.bin"),deleted.resolve(expected.version()+"-"+UUID.randomUUID()+".bin"),StandardCopyOption.ATOMIC_MOVE);
        }
    }
    private static void version(Record record,String version)throws IOException{if(record==null||!record.row.version().equals(version))throw new IOException("存档已变化，请刷新后重试");}
    private static NetplaySaveState.Parts checkpoint(byte[] bytes,NetplaySaveState.Identity expected)throws IOException{
        if(bytes==null||bytes.length>MAX_CHECKPOINT)throw new IOException("MD存档超出大小上限");
        try{return NetplaySaveState.decode(bytes,expected);}catch(IllegalArgumentException invalid){throw new IOException("MD存档损坏或身份不符",invalid);}
    }
    private static byte[] encode(String owner,String name,int players,byte[] checkpoint,long frame)throws IOException{
        var payload=new ByteArrayOutputStream();try(var out=new DataOutputStream(payload)){out.writeInt(MAGIC);out.writeInt(1);out.writeUTF(owner);out.writeUTF(name(name));out.writeByte(players);out.writeInt(checkpoint.length);out.write(checkpoint);}
        return NetplaySaveState.encode(new NetplaySaveState.Parts(outer(owner),frame,payload.toByteArray(),new byte[0],new byte[0]));
    }
    private static Record decode(String expectedOwner,byte[] bytes,long modified)throws IOException{
        try{var parts=NetplaySaveState.decode(bytes,expectedOwner==null?null:outer(expectedOwner));
            if(parts.ram().length!=0||parts.rtc().length!=0)throw new IOException("MD元数据格式错误");
            try(var in=new DataInputStream(new ByteArrayInputStream(parts.state()))){
                if(in.readInt()!=MAGIC||in.readInt()!=1)throw new IOException("MD存档版本不兼容");
                String owner=in.readUTF(),name=name(in.readUTF());validateOwner(owner);int players=in.readUnsignedByte(),size=in.readInt();
                if((expectedOwner!=null&&!expectedOwner.equals(owner))||!parts.identity().equals(outer(owner))||players<1||players>2||size<1||size>MAX_CHECKPOINT||in.available()!=size)throw new IOException("MD存档元数据无效");
                byte[] checkpoint=in.readNBytes(size);var state=checkpoint(checkpoint,null);
                if(parts.frame()!=state.frame())throw new IOException("MD存档帧号不符");
                return new Record(new Row(owner,id(owner),NetplaySaveState.hash(bytes),name,players,state.identity(),state.frame(),modified,bytes.length),checkpoint);
            }
        }catch(IllegalArgumentException invalid){throw new IOException("MD存档损坏，原件保留",invalid);}
    }
}
