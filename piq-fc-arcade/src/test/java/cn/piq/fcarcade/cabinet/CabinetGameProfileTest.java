package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.layout.CabinetVideoGeometry;
import java.util.List;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetGameProfileTest {
    private CabinetGameManifest game(String name,String hash){return new CabinetGameManifest("piq_native_arcade:mame",List.of(new CabinetGameManifest.Entry(name,hash,123)));}
    private CabinetGameProfile profile(int revision){return new CabinetGameProfile("三国战纪",4,CabinetGameProfile.Orientation.LANDSCAPE,CabinetGameProfile.Aspect.FOUR_THREE,revision);}
    @Test void nameAndStructuredMarksAreBounded(){
        assertEquals("三国战纪",new CabinetGameProfile(" 三国战纪 ",4,CabinetGameProfile.Orientation.LANDSCAPE,CabinetGameProfile.Aspect.CORE,0).name());
        for(String name:new String[]{"a".repeat(65),"a\nb","§c游戏","a\u202Eb"})assertThrows(IllegalArgumentException.class,()->new CabinetGameProfile(name,1,CabinetGameProfile.Orientation.UNKNOWN,CabinetGameProfile.Aspect.CORE,0));
        for(int count:new int[]{-1,5,255})assertThrows(IllegalArgumentException.class,()->new CabinetGameProfile("",count,CabinetGameProfile.Orientation.UNKNOWN,CabinetGameProfile.Aspect.CORE,0));
    }
    @Test void unmarkedNeverClaimsCompatibility(){assertTrue(CabinetGameProfile.EMPTY.summary().contains("人数未标注"));assertEquals("kov.zip",CabinetGameProfile.EMPTY.label("kov.zip"));}
    @Test void profilesSurviveSaveReloadWithoutRewritingContentIdentity(){
        var game=game("kov.zip","a".repeat(64));String identity=game.contentId();var data=new CabinetGameProfiles();
        assertTrue(data.update(game,profile(0)));var raw=data.save(new CompoundTag(),null);var restored=CabinetGameProfiles.load(raw,null);
        assertEquals(profile(1),restored.find(game));assertEquals(identity,game.contentId());
        assertEquals("kov.zip",game.files().getFirst().name());
    }
    @Test void samePrimaryHashKeepsProfileAcrossFilenameOrBiosChanges(){
        var data=new CabinetGameProfiles();var first=game("kov.zip","a".repeat(64));assertTrue(data.update(first,profile(0)));
        assertEquals(profile(1),data.find(game("another.zip","a".repeat(64))));
        assertEquals(CabinetGameProfile.EMPTY,data.find(game("kov.zip","b".repeat(64))));
    }
    @Test void staleAdminRevisionIsRejectedWithoutOverwrite(){
        var data=new CabinetGameProfiles();var g=game("kov.zip","a".repeat(64));assertTrue(data.update(g,profile(0)));
        assertFalse(data.update(g,new CabinetGameProfile("覆盖",2,CabinetGameProfile.Orientation.PORTRAIT,CabinetGameProfile.Aspect.CORE,0)));assertEquals(profile(1),data.find(g));
        assertTrue(data.update(g,profile(1)));assertEquals(profile(2),data.find(g));
    }
    @Test void maximumRevisionDoesNotWrap(){assertThrows(IllegalStateException.class,()->profile(Integer.MAX_VALUE).next());}
    @Test void badSingleProfileDoesNotDestroyOthers(){
        var data=new CabinetGameProfiles();var g=game("kov.zip","a".repeat(64));data.update(g,profile(0));var root=data.save(new CompoundTag(),null);
        var bad=new CompoundTag();bad.putString("key","piq_native_arcade:mame/"+"b".repeat(64));bad.putInt("orientation",100);
        root.getList("profiles",Tag.TAG_COMPOUND).add(bad);var restored=CabinetGameProfiles.load(root,null);
        assertEquals(profile(1),restored.find(g));assertEquals(CabinetGameProfile.EMPTY,restored.find(game("b.zip","b".repeat(64))));
    }
    @Test void explicitRatioIsFinalDisplayRatioAfterAllCoreRotations(){
        for(var aspect:CabinetGameProfile.Aspect.values())for(int rotation=0;rotation<4;rotation++){
            double raw=aspect.rawAspect(1.5,rotation,4d/3),result=CabinetVideoGeometry.displayAspect(raw,rotation);
            double expected=switch(aspect){case CORE->rotation%2==0?1.5:1/1.5;case FOUR_THREE,FILL->4d/3;case THREE_FOUR->3d/4;case SIXTEEN_NINE->16d/9;case SQUARE->1;};
            assertEquals(expected,result,1e-9);
            var frame=CabinetVideoGeometry.frame(true,0,raw,rotation,true);assertEquals(expected,frame.displayAspect(),1e-9);
            assertEquals(4,frame.vertices().size()); // No pixel cropping or core changes.
        }
    }
    @Test void emptyProfilesPreserveTheCoreAspectExactly(){for(int r=0;r<4;r++)assertEquals(1.37,CabinetGameProfile.EMPTY.aspect().rawAspect(1.37,r,4d/3));}
}
