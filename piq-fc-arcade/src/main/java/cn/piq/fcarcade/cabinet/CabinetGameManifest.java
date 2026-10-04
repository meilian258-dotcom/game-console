package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Only opaque, explicitly selected game data. Never a native runtime or a client path. */
public record CabinetGameManifest(String backend,List<Entry> files) {
    public static final int CHUNK=24576,MAX_FILES=5,MAX_FILE=96*1024*1024,MAX_LEGACY_FILE=64*1024*1024,MAX_TOTAL=128*1024*1024;
    public static final Set<String> BIOS=Set.of("neogeo.zip","qsound_hle.zip","qsound.zip","pgm.zip");
    public record Entry(String name,String sha256,int size) {
        public Entry {
            if(name==null||name.isBlank()||name.length()>128||name.equals(".")||name.equals("..")
                    ||name.endsWith(".")||name.endsWith(" ")||name.chars().anyMatch(c->c<32||"/\\:*?\"<>|".indexOf(c)>=0)
                    ||!hash(sha256)||size<1||size>MAX_FILE)throw new IllegalArgumentException("Invalid game manifest file");
            String stem=name.split("\\.",2)[0].toUpperCase(Locale.ROOT);
            if(stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"))throw new IllegalArgumentException("Reserved game filename");
        }
    }
    public CabinetGameManifest {
        if(backend==null||!backend.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")||backend.length()>128
                ||files==null||files.isEmpty()||files.size()>MAX_FILES)throw new IllegalArgumentException("Invalid game manifest");
        files=List.copyOf(files);long total=0;Set<String> names=new HashSet<>();
        for(int i=0;i<files.size();i++){
            Entry entry=Objects.requireNonNull(files.get(i));String name=entry.name.toLowerCase(Locale.ROOT);
            if(entry.size>MAX_LEGACY_FILE&&(i!=0||!name.endsWith(".zip")))throw new IllegalArgumentException("Only a main arcade ZIP may exceed 64 MiB");
            if(!names.add(name))throw new IllegalArgumentException("Duplicate game filename");
            if(i==0){if(!(name.endsWith(".nes")||name.endsWith(".sfc")||name.endsWith(".smc")||name.endsWith(".gba")||name.endsWith(".zip"))||BIOS.contains(name))throw new IllegalArgumentException("Unsupported game data");}
            else if(!files.getFirst().name.toLowerCase(Locale.ROOT).endsWith(".zip")||!BIOS.contains(name))throw new IllegalArgumentException("Only declared arcade BIOS companions are allowed");
            total+=entry.size;
        }
        if(total>MAX_TOTAL)throw new IllegalArgumentException("Game and BIOS exceed 128 MiB");
    }
    public long size(){return files.stream().mapToLong(Entry::size).sum();}
    public String gameHash(){return files.getFirst().sha256;}
    public String contentId(){StringBuilder s=new StringBuilder(backend);for(Entry e:files)s.append('\n').append(e.name).append('\n').append(e.sha256).append('\n').append(e.size);return digest(s.toString().getBytes(StandardCharsets.UTF_8));}
    public static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
