package cn.piq.fcarcade.netplay;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetplayProfileTest {
    @Test void managedWorkingCopiesDoNotSendUnicodeAbsoluteContentThroughNativeArgv()throws Exception {
        String source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/netplay/NetplayProcess.java"));
        assertTrue(source.contains("args.add(profile.contentName())"));
        assertTrue(source.contains(".directory(directory.toFile())"));
        assertTrue(source.contains("workspace.start(builder)"));
        assertFalse(source.contains("Files.createTempDirectory("));
    }
    NetplayProfile p(String name,Map<String,String> options,int size){return new NetplayProfile(getClass(),"/core.dll","a".repeat(64),name,options,1,48000,size);}
    @Test void namesCannotEscapePrivateDirectory(){for(String n:List.of("../x.zip","a/b.nes","a\\b.nes","x.cfg","x.zip\n",".zip"))assertThrows(IllegalArgumentException.class,()->p(n,Map.of(),1024));}
    @Test void profileRejectsConfigInjection(){assertThrows(IllegalArgumentException.class,()->p("a.zip",Map.of("x","true\nother = false"),1024));assertThrows(IllegalArgumentException.class,()->p("a.zip",Map.of("x\n","a"),1024));}
    @Test void romAllocationBounded(){assertThrows(IllegalArgumentException.class,()->p("a.zip",Map.of(),64*1024*1024+1));assertThrows(IllegalArgumentException.class,()->p("a.zip",Map.of(),0));}
    @Test void oldFcProfileUnchanged(){var p=NetplayProfile.fc();assertEquals(44100,p.sampleRate());assertEquals("content.nes",p.contentName());assertEquals("NTSC",p.options().get("mesen_region"));assertEquals("2b3fbe286995c80ebbc85239fd28c8fa07b1011cc69c7f9021816429e3473885",p.sha());}
    @Test void ownedConfigImmutable(){var values=new HashMap<String,String>();values.put("z","disabled");var profile=p("kof97.zip",values,100);values.put("z","enabled");assertEquals("z = \"disabled\"\n",profile.config());assertThrows(UnsupportedOperationException.class,()->profile.options().put("x","y"));}
    @Test void adjunctAuthorityAndCloseAreWired()throws Exception{
        String root="src/main/java/cn/piq/fcarcade/";
        var server=Files.readString(Path.of(root+"cabinet/CabinetNetplay.java"));
        for(String guard:List.of("CabinetRooms.authorized(p,a.room(),a.member())","run.server()!=p.getServer()","run.relay().renew(current)","NetplayNetwork.retire(run.relay())"))assertTrue(server.contains(guard));
        var client=Files.readString(Path.of(root+"client/cabinet/CabinetClientBackends.java"));
        for(String guard:List.of("generation!=token||launch!=request||room!=assignment||netplayGrant!=grant||!current()","if(netplay()){startNetplay(chosen,remember);return;}","room=null;netplayGrant=null","if(netplay()){if(serverNetplayInput()&&current())CabinetRoomNetwork.send(new CabinetRoomNetwork.Reset(room.room(),room.member(),inputSequence++));else if(emulator!=null)emulator.clearInput();return;}"))assertTrue(client.contains(guard));
    }
}
