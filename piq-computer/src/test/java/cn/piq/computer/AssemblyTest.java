package cn.piq.computer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AssemblyTest {
    @Test void allSlotsRoundTrip(){int mask=0;for(var p:Assembly.Part.values()){assertTrue(Assembly.installable(mask,p),p.name());mask|=p.bit();assertFalse(Assembly.installable(mask,p));}assertEquals(255,mask);for(int i=7;i>=0;i--){var p=Assembly.Part.values()[i];assertTrue(Assembly.removable(mask,p),p.name());mask&=~p.bit();}assertEquals(0,mask);}
    @Test void everyMaskPreservesDependencies(){for(int m=0;m<256;m++)for(var p:Assembly.Part.values()){
        if(Assembly.installable(m,p)){assertFalse(Assembly.has(m,p));assertEquals(p.requires,m&p.requires);assertEquals(m,(m|p.bit())&~p.bit());}
        if(Assembly.removable(m,p))for(var other:Assembly.Part.values())if(other!=p&&Assembly.has(m,other))assertEquals(0,other.requires&p.bit());
    }}
    @Test void oneMemoryModuleInEitherSlotBoots(){assertTrue(Assembly.ready(127));assertTrue(Assembly.ready(255&~Assembly.Part.RAM.bit()));assertFalse(Assembly.ready(255&~(Assembly.Part.RAM.bit()|Assembly.Part.RAM_2.bit())));}
    @Test void cannotRemoveCpuWithCooler(){assertFalse(Assembly.removable(255,Assembly.Part.CPU));assertFalse(Assembly.removable(255,Assembly.Part.MOTHERBOARD));assertTrue(Assembly.removable(255&~Assembly.Part.COOLER.bit(),Assembly.Part.CPU));}
    @Test void allOtherRequiredPartsMatter(){for(var p:Assembly.Part.values())if(p!=Assembly.Part.RAM&&p!=Assembly.Part.RAM_2)assertFalse(Assembly.ready(255&~p.bit()),p.name());}
    @Test void noInventedBits(){assertEquals(255,Assembly.clean(-1));assertEquals(0,Assembly.clean(256));}
    @Test void dependenciesFollowOrder(){assertFalse(Assembly.installable(0,Assembly.Part.CPU));assertFalse(Assembly.installable(1,Assembly.Part.COOLER));assertTrue(Assembly.installable(3,Assembly.Part.COOLER));assertTrue(Assembly.installable(0,Assembly.Part.HDD));assertTrue(Assembly.installable(0,Assembly.Part.PSU));}
}
