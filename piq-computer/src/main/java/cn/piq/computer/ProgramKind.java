package cn.piq.computer;
import java.nio.file.*;
import java.util.Locale;
/** Stable local configuration values; never selected by a server packet. */
public enum ProgramKind {
    HARDWARE("硬件测试"), PVZ("植物大战僵尸"), FLASH("Flash");
    public final String label;ProgramKind(String label){this.label=label;}
    public boolean accepts(Path path){String n=path.getFileName()==null?"":path.getFileName().toString().toLowerCase(Locale.ROOT);return this==PVZ?n.equals("main.pak"):this==FLASH&&n.endsWith(".swf");}
    public static ProgramKind parse(String key){try{return valueOf(key);}catch(Exception e){return HARDWARE;}}
}
