package cn.piq.fcarcade.server;

import cn.piq.fcarcade.server.hosted.ServerCoreFiles;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, metadata-only management of existing FC envelopes. No migration or restore. */
final class FcSaveManagementStore {
    static final int MAX_FILE=2*1024*1024+4096,MAX_SCAN=512,MAX_ROWS=64,READ_BUDGET=8*1024*1024;
    private static final int MAGIC=0x50465153;
    private final Path root;
    record Row(String id,String version,int format,String key,String rom,String name,int players,long modified,long bytes) {}
    record Listing(List<Row> rows,boolean truncated) {Listing{rows=List.copyOf(rows);}}
    private record Decoded(Row row,byte[] state) {}
    FcSaveManagementStore(Path root){this.root=root.toAbsolutePath().normalize();}
    static String owner(String key){String player=PlayerSaveCatalogKey.normalize(key);return player.isEmpty()?"":player.split("\\|",-1)[1];}
    static boolean allowed(Row row,UUID player,boolean op){return op||player!=null&&player.toString().equals(owner(row.key()));}
    static boolean allowed(Row row,UUID player,boolean op,UUID card){return allowed(row,player,op)||cn.piq.fcarcade.home.CartridgeSaveIdentity.owns(row.key(),card);}
    static String source(String key){return key.startsWith("core|nes-netplay-")?"FC · Netplay · "+(owner(key).isEmpty()?"卡带":"个人"):key.startsWith("server-home-v1|")?"FC · 旧服务器托管存档":key.startsWith("player-home-v1|")?"FC · 旧玩家串流存档":owner(key).isEmpty()?"FC · 机器 / 卡带存档":"FC · 个人存档";}
    Listing list(String rom,UUID player,boolean op)throws IOException{
        return list(rom,player,op,null);
    }
    Listing list(String rom,UUID player,boolean op,UUID card)throws IOException{
        requireRom(rom);if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return new Listing(List.of(),false);ServerCoreFiles.directory(root,false);
        var rows=new ArrayList<Row>();long budget=0;int count=0;boolean truncated=false;
        try(var entries=Files.newDirectoryStream(root)){
            for(Path path:entries){if(++count>MAX_SCAN){truncated=true;break;}String name=path.getFileName().toString();
                if(!name.matches("[0-9a-f]{64}\\.sav")||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                long size=Files.size(path);if(size<48||size>MAX_FILE)continue;
                try{
                    // Inspect only bounded header fields first; unrelated ROMs/owners never consume the payload budget.
                    if(!headerMatches(path,rom,player,op,card))continue;
                    if((budget+=size)>READ_BUDGET){truncated=true;break;}
                    var row=read(name.substring(0,64)).row();if(row.rom().equals(rom)&&allowed(row,player,op,card)){rows.add(row);if(rows.size()==MAX_ROWS){truncated=true;break;}}
                }
                catch(IOException|IllegalArgumentException invalid){/* Malformed originals are preserved, never repaired or quarantined. */}
            }
        }
        rows.sort(Comparator.comparingLong(Row::modified).reversed());return new Listing(rows,truncated);
    }
    private static boolean headerMatches(Path path,String rom,UUID player,boolean op,UUID card)throws IOException{
        try(var in=new DataInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){
            if(in.readInt()!=MAGIC)return false;int version=in.readInt();if(version<1||version>3)return false;
            String key=version>=2?in.readUTF():"";if(key.length()>1024)return false;
            return rom.equals(in.readUTF())&&(op||player!=null&&player.toString().equals(owner(key))||cn.piq.fcarcade.home.CartridgeSaveIdentity.owns(key,card));
        }
    }
    Row current(String id)throws IOException{return read(id).row();}
    void rename(String id,String expected,String name)throws IOException{
        name=ArcadeSaveStore.normalizeSlotName(name);if(name.isBlank())throw new IOException("存档名称不能为空");
        var value=read(id);var row=value.row();check(row,expected);
        if(row.format()!=3)throw new IOException("旧版存档只读；未转换格式");
        byte[] next=encode(row,value.state(),name);
        Path temporary=null;
        try{
            ServerCoreFiles.directory(root,false);temporary=Files.createTempFile(root,".save-name-",".tmp");
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                var bytes=ByteBuffer.wrap(next);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            }
            // Re-read immediately before commit; the player's old listing is never an overwrite authority.
            check(read(id).row(),expected);
            Files.move(temporary,path(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temporary=null;
        }finally{if(temporary!=null)Files.deleteIfExists(temporary);}
    }
    void delete(String id,String expected)throws IOException{
        var row=read(id).row();check(row,expected);if(row.format()!=3)throw new IOException("旧版存档只读；原文件保留");
        Path deleted=root.resolve("deleted-by-cartridge-manager");ServerCoreFiles.directory(deleted,true);
        // A fresh unadvertised suffix also preserves any externally retained copy of this same version.
        Path target=deleted.resolve(id+"-"+expected+"-"+UUID.randomUUID()+".sav");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("已有同版本回收副本，请先检查，未覆盖");
        check(read(id).row(),expected);Files.move(path(id),target,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void check(Row row,String expected)throws IOException{if(expected==null||!expected.matches("[0-9a-f]{64}")||!row.version().equals(expected))throw new IOException("存档已变化，请刷新后重新操作");}
    private Path path(String id){if(id==null||!id.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("存档标识无效");return root.resolve(id+".sav");}
    private Decoded read(String id)throws IOException{
        Path file=path(id);byte[] bytes=ServerCoreFiles.read(file,48,MAX_FILE);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=MAGIC)throw new IOException("存档类型无效");int version=in.readInt();if(version<1||version>3)throw new IOException("存档版本无效");
            String key=version>=2?in.readUTF():"",rom=in.readUTF();requireRom(rom);
            String name=version==3?in.readUTF():"";int players=version==3?in.readUnsignedByte():1;
            if(key.length()>1024||name.length()>32||name.chars().anyMatch(Character::isISOControl)||players<1||players>2)throw new IOException("存档元数据无效");
            int length=in.readInt();if(length<1||length>2*1024*1024||in.available()!=32+length)throw new IOException("存档长度无效");
            byte[] digest=in.readNBytes(32),state=in.readNBytes(length);
            if(!MessageDigest.isEqual(digest,HexFormat.of().parseHex(ServerCoreFiles.sha256(state))))throw new IOException("存档摘要无效");
            if(version>=2&&!id.equals(ServerCoreFiles.sha256((key+"|"+rom).getBytes(java.nio.charset.StandardCharsets.UTF_8))))throw new IOException("存档身份无效");
            return new Decoded(new Row(id,ServerCoreFiles.sha256(bytes),version,key,rom,name,players,Math.max(0,Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis()),bytes.length),state);
        }
    }
    private static byte[] encode(Row row,byte[] state,String name)throws IOException{
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(3);out.writeUTF(row.key());out.writeUTF(row.rom());out.writeUTF(name);out.writeByte(row.players());out.writeInt(state.length);out.write(HexFormat.of().parseHex(ServerCoreFiles.sha256(state)));out.write(state);
        }return bytes.toByteArray();
    }
    private static void requireRom(String rom){if(rom==null||!rom.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("卡带 ROM 标识无效");}
}
