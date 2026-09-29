package cn.piq.sfcarcade.server;

import cn.piq.sfcarcade.core.SfcRomImage;
import cn.piq.sfcarcade.rom.SfcRomRepository;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free gate + actual single-file IO test; no Minecraft server construction. */
public final class SfcDownloadGateSelfTest {
    private static int checks;
    private static void check(boolean pass,String why){checks++;if(!pass)throw new AssertionError(why);}
    public static int verify()throws Exception{
        checks=0;Object connection=new Object();String sha="a".repeat(64);AtomicInteger authority=new AtomicInteger(),reads=new AtomicInteger();
        var gate=new SfcDownloadGate(sha,connection);
        for(int i=0;i<10_000;i++)check(!gate.admit("0".repeat(64),connection,i,()->{authority.incrementAndGet();return true;}),"unknown hash has no grant");
        check(authority.get()==0,"unknown hashes do not reach permissions or IO");
        check(!gate.admit(sha,new Object(),0,()->{authority.incrementAndGet();return true;}),"different actual connection rejected");
        check(!gate.admit(sha,connection,0,()->{authority.incrementAndGet();return false;}),"permissions denied");
        check(!gate.admit(sha,connection,99,()->true),"denied attempts still rate limited");
        check(gate.admit(sha,connection,100,()->{check(!gate.admit(sha,connection,200,()->true),"callback reentry refused");return true;}),"authorized fresh request");
        check(!gate.admit(sha,connection,1000,()->true),"in-flight duplicate refused");gate.complete();
        check(gate.admit(sha,connection,1000,()->true),"later completed request may retry");gate.complete();
        Path root=Files.createTempDirectory("piq-sfc-download-gate-");
        try{
            byte[] legal=new byte[SfcRomImage.MIN_ROM_BYTES];for(int i=0;i<legal.length;i++)legal[i]=(byte)i;
            String actualHash=SfcRomImage.fromBytes(legal).sha256();
            Path game=root.resolve("legal.sfc");Files.write(game,legal);
            var repository=new SfcRomRepository(root);var valid=new SfcDownloadGate(actualHash,connection);
            if(valid.admit(actualHash,connection,0,()->true)){reads.incrementAndGet();check(Arrays.equals(legal,repository.readNamedVerified("legal.sfc",actualHash)),"authorized single file download is exact");}
            check(reads.get()==1,"normal authorized download performs one read");
            // A non-ROM sibling and an inaccessible/non-file entry need not be traversed.
            Files.createDirectory(root.resolve("do-not-scan.sfc"));
            check(Arrays.equals(legal,repository.readNamedVerified("legal.sfc",actualHash)),"no catalogue scan is required");
            for(String name:new String[]{"../legal.sfc","missing.sfc","do-not-scan.sfc"}){boolean denied=false;try{repository.readNamedVerified(name,actualHash);}catch(java.io.IOException expected){denied=true;}check(denied,"invalid exact file rejected: "+name);}
            boolean denied=false;try{repository.readNamedVerified("legal.sfc","0".repeat(64));}catch(java.io.IOException expected){denied=true;}check(denied,"changed identity fails hash verification");
            byte[] headered=new byte[legal.length+512];System.arraycopy(legal,0,headered,512,legal.length);Files.write(root.resolve("legal.smc"),headered);
            check(Arrays.equals(headered,repository.readNamedVerified("legal.smc",actualHash)),"old headered ROM download remains compatible");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        return checks;
    }
    public static void main(String[] args)throws Exception{if(args.length>0)for(Class<?> c:List.of(SfcDownloadGate.class,SfcRomRepository.class))if(!Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()))throw new AssertionError("wrong production origin "+c);System.out.println("{\"ok\":true,\"assertions\":"+verify()+",\"unknown_hash_permission_calls\":0,\"unknown_hash_file_reads\":0,\"minecraft_started\":false}");}
}
