package cn.piq.computer;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PvzJniContractTest {
    private String source(String n)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/computer/client/"+n+".java"));}
    @Test void optInLocalDefaultAndNoRemoteRuntime()throws Exception{String s=source("ComputerPrograms");assertTrue(s.contains("OPTIONS.getProperty(\"pvz.engine\",\"process\")"));assertTrue(s.contains("if(busy()){notice=\"请先结束当前程序再切换运行器\""));assertTrue(s.indexOf("if(ComputerStreams.remote(open.id()))")<s.indexOf("loading=IO.submit"));assertFalse(s.contains("new PvzJniRuntime"));}
    @Test void explicitWarningAndFallback()throws Exception{String s=source("ComputerProgramScreen");assertTrue(s.contains("new ConfirmScreen"));assertTrue(s.contains("整个 Minecraft 崩溃"));assertTrue(s.contains("if(accepted)ComputerPrograms.pvzJni(true)"));String b=source("PvzComputerBackend");assertTrue(b.contains("this(root,file,player,false)"));assertTrue(b.contains("jni?new PvzJniRuntime(root,file,player):new PvzRuntime(root,file,player)"));}
}
