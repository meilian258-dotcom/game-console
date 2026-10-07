package cn.piq.fcarcade.session;

import cn.piq.fcarcade.core.NesCores;
import cn.piq.fcarcade.rom.INesHeader;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NamcoCoreIdentity43Test {
    private static final String ROM="ab".repeat(32);
    private static INesHeader header(int mapper){return new INesHeader(INesHeader.Format.INES,mapper,0,32768,8192,false,false);}
    private static byte[] envelope(NesCoreVariant variant){
        ByteBuffer b=ByteBuffer.allocate(128);
        b.putInt(variant==NesCoreVariant.MAPPER19_V1?0x504E3139:0x50515a31).putInt(1);
        b.put(HexFormat.of().parseHex(variant.stateNamespace().split("/")[1]));
        b.put(HexFormat.of().parseHex(ROM));b.position(104);b.putInt(65536);return b.array();
    }
    @Test void existingOrdinalsAndNamespacesStayFrozen(){
        assertEquals(0,NesCoreVariant.LEGACY.ordinal());assertEquals(1,NesCoreVariant.ZAPPER_V1.ordinal());assertEquals(2,NesCoreVariant.MAPPER19_V1.ordinal());
        assertEquals("nes-legacy-v1",NesCoreVariant.LEGACY.stateNamespace());
        assertEquals("nes-zapper-v1/b8b2a72543fa49f286e645bf4e5b13840485c2ceddb66a4734f01b08c25ff64c",NesCoreVariant.ZAPPER_V1.stateNamespace());
        assertEquals(NesCoreVariant.MAPPER19_V1,NesCoreVariant.fromNetwork(2));assertThrows(IllegalArgumentException.class,()->NesCoreVariant.fromNetwork(5));
        assertThrows(IllegalArgumentException.class,()->NesCoreVariant.fromNetwork(-1));
    }
    @Test void mapper19IsExplicitAndNeverUsesTheGunAbi(){
        assertEquals(NesCoreVariant.LIBRETRO_V1,NesCoreVariant.forRom(header(19),false));
        assertThrows(IllegalArgumentException.class,()->NesCoreVariant.forRom(header(19),true));
        for(int mapper:new int[]{0,1,2,3,4,140}){
            assertEquals(NesCoreVariant.LIBRETRO_V1,NesCoreVariant.forRom(header(mapper),false));
            assertEquals(NesCoreVariant.LIBRETRO_ZAPPER_V1,NesCoreVariant.forRom(header(mapper),true));
        }
        assertEquals(NesCoreVariant.LIBRETRO_V1,NesCoreVariant.forRom(header(5),false));
        assertEquals(NesCoreVariant.LIBRETRO_V1,NesCoreVariant.forRom(new INesHeader(INesHeader.Format.NES_2_0,19,0,32768,8192,false,false),false));
        assertThrows(IllegalArgumentException.class,()->NesCoreVariant.forRom(header(4095),false));
    }
    @Test void allThreeStateEnvelopesAreMutuallyExcluded(){
        byte[] old=ByteBuffer.allocate(10).putInt(0x50465131).putInt(65536).putShort((short)0).array();
        assertTrue(NesCoreVariant.LEGACY.acceptsStateHeader(old,ROM));
        for(var variant:new NesCoreVariant[]{NesCoreVariant.MAPPER19_V1,NesCoreVariant.ZAPPER_V1}){
            assertFalse(variant.acceptsStateHeader(old,ROM));
            byte[] state=envelope(variant);
            for(var receiver:NesCoreVariant.values())assertEquals(receiver==variant,receiver.acceptsStateHeader(state,ROM));
        }
    }
    @Test void headerRejectsOtherRomModuleVersionAndInvalidSizes(){
        var variant=NesCoreVariant.MAPPER19_V1;
        assertFalse(variant.acceptsStateHeader(null,ROM));assertFalse(variant.acceptsStateHeader(new byte[127],ROM));
        assertFalse(variant.acceptsStateHeader(envelope(variant),"cd".repeat(32)));assertFalse(variant.acceptsStateHeader(envelope(variant),"bad"));
        for(int offset:new int[]{0,4,8,40}){byte[] bad=envelope(variant);bad[offset]^=1;assertFalse(variant.acceptsStateHeader(bad,ROM));}
        for(int size:new int[]{-1,0,1,65535,64*1024*1024+65536}){byte[] bad=envelope(variant);ByteBuffer.wrap(bad).putInt(104,size);assertFalse(variant.acceptsStateHeader(bad,ROM));}
        assertTrue(variant.acceptsStateHeader(envelope(variant),ROM.toUpperCase(java.util.Locale.ROOT)));
    }
    @Test void newCoreSlotsCannotCollideWithOldMachinePersonalOrGunSlots(){
        var keys=new HashSet<String>();
        for(String old:new String[]{"minecraft:overworld|1,64,2|LOCKSTEP","player|a|global-slot|1","player|a|global-slot|2","player|a|global-slot|3"}){
            assertEquals(old,NesCoreVariant.LEGACY.saveKey(old));
            assertEquals("core|"+NesCoreVariant.ZAPPER_V1.stateNamespace()+"|"+old,NesCoreVariant.ZAPPER_V1.saveKey(old));
            for(var variant:NesCoreVariant.values()){
                assertTrue(keys.add(variant.saveKey(old)));assertTrue(keys.add("server-home-v1|"+variant.saveKey(old)));
            }
        }
        assertEquals(40,keys.size());
    }
    @Test void moduleResourceSelectionRetainsBothOldBinaries(){
        assertEquals("/core/nes_rust_wasm_bg.wasm",NesCores.moduleResource(NesCoreVariant.LEGACY));
        assertEquals(cn.piq.fcarcade.core.wasm.ZapperWasmNesCore.MODULE_RESOURCE,NesCores.moduleResource(NesCoreVariant.ZAPPER_V1));
        assertEquals("/core/nes_mapper19_v1.wasm",NesCores.moduleResource(NesCoreVariant.MAPPER19_V1));
    }
}
