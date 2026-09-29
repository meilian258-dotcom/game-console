// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import java.nio.file.*;
import java.util.*;

/** Actual production scope/store tests in an owned test directory, no Minecraft/native core. */
public final class GbaServerScopeProbe {
    private static int assertions;
    private static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    private static void rejects(Runnable call){assertions++;try{call.run();throw new AssertionError("invalid scope accepted");}catch(IllegalArgumentException|NullPointerException expected){}}
    public static void main(String[] args)throws Exception {
        if(args.length<1||args.length>2)throw new IllegalArgumentException("new owned test directory, optional exact final addon JAR");
        Path root=Path.of(args[0]).toAbsolutePath().normalize();if(Files.exists(root))throw new IllegalArgumentException("new test directory required");Files.createDirectory(root);
        if(args.length==2){Path origin=Path.of(args[1]).toRealPath();for(Class<?> type:List.of(GbaSaveScope.class,GbaSaveStore.class,GbaProtocol.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(origin),"actual final production origin");}
        UUID a=UUID.fromString("01010101-0101-0101-0101-010101010101"),b=UUID.fromString("02020202-0202-0202-0202-020202020202");
        GbaSaveScope first=GbaSaveScope.of("server:example.test:25565",a);
        check(first.equals(GbaSaveScope.of("server:example.test:25565",a)),"stable repeated context");
        for(String context:List.of("server:example.test:25566","server:other.test:25565","world:example.test:25565","server:tcp://example.test:25565","world:G:/world-a","world:G:/world-b"))check(!first.resolve(root).equals(GbaSaveScope.of(context,a).resolve(root)),"distinct server/world identity "+context);
        check(!first.resolve(root).equals(GbaSaveScope.of("server:example.test:25565",b).resolve(root)),"different player never shares save directory");
        for(String context:Arrays.asList(null,""," ","server:","world:","example.test","server:a\nb","server:a\u0000b","server:"+"x".repeat(2048)))rejects(()->GbaSaveScope.of(context,a));
        rejects(()->GbaSaveScope.of("server:example.test",null));rejects(()->new GbaSaveScope("../escape",a));
        Path computed=GbaSaveScope.of("server:../../not-a-path",a).resolve(root);
        check(computed.startsWith(root.resolve("scoped-v1"))&&computed.getNameCount()==root.getNameCount()+3,"raw context cannot escape path");
        check(!Files.exists(computed),"scope computation has no filesystem effects");
        String rom="A".repeat(64);byte[] legacyBytes=new byte[32768];legacyBytes[9]=71;
        GbaSaveStore legacy=new GbaSaveStore(root.resolve("mgba-e31759b"),rom);legacy.save(legacyBytes);
        Path legacyPath=root.resolve("mgba-e31759b").resolve(rom).resolve("sram.bin");var before=Files.getLastModifiedTime(legacyPath);
        var owners=List.of(first,GbaSaveScope.of("server:other.test:25565",a),GbaSaveScope.of("server:example.test:25565",b),GbaSaveScope.of("world:G:/world-a",a));
        for(int i=0;i<owners.size();i++){
            GbaSaveStore store=new GbaSaveStore(owners.get(i).resolve(root).resolve("mgba-e31759b"),rom);
            check(store.load().length==0,"no implicit read/migration from legacy or another owner");
            byte[] saved=new byte[32768];saved[9]=(byte)(i+1);store.save(saved);
            check(Arrays.equals(saved,new GbaSaveStore(owners.get(i).resolve(root).resolve("mgba-e31759b"),rom).load()),"actual isolated store reopens");
        }
        for(int i=0;i<owners.size();i++)check(new GbaSaveStore(owners.get(i).resolve(root).resolve("mgba-e31759b"),rom).load()[9]==i+1,"later owner writes never alter earlier owner");
        check(Arrays.equals(legacyBytes,Files.readAllBytes(legacyPath)),"legacy save bytes preserved");
        check(before.equals(Files.getLastModifiedTime(legacyPath)),"legacy save mtime preserved");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\""+(args.length==2?"final-jar-only":"fresh-isolated-compile")+"\",\"actual_store_io\":true,\"legacy_save_untouched\":true,\"minecraft_started\":false,\"native_core_started\":false}");
    }
}
