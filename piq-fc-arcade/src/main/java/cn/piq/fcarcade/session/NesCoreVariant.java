package cn.piq.fcarcade.session;

/** Server selected core identity. Ordinary cartridges never implicitly select the gun core. */
public enum NesCoreVariant {
    LEGACY, ZAPPER_V1, MAPPER19_V1, LIBRETRO_V1, LIBRETRO_ZAPPER_V1;
    public boolean isZapper() { return this==ZAPPER_V1||this==LIBRETRO_ZAPPER_V1; }
    public boolean isLibretro() { return this==LIBRETRO_V1||this==LIBRETRO_ZAPPER_V1; }
    public static NesCoreVariant fromNetwork(int value) {
        if(value<0||value>=values().length)throw new IllegalArgumentException("Unknown NES core variant");
        return values()[value];
    }
    public String stateNamespace() {
        return switch(this) {
            case LEGACY -> "nes-legacy-v1";
            case ZAPPER_V1 -> "nes-zapper-v1/c8d8824e5caf727678c642e6b0539deaa7c0084f33524d96779d90c7b5da79ef";
            case MAPPER19_V1 -> "nes-mapper19-v1/"+cn.piq.fcarcade.core.wasm.NamcoWasmNesCore.MODULE_SHA256;
            case LIBRETRO_V1 -> "nes-libretro-mesen-v1/"+cn.piq.fcarcade.core.libretro.LibretroNesCore.PROFILE_SHA256;
            case LIBRETRO_ZAPPER_V1 -> "nes-libretro-mesen-zapper-v1/"+cn.piq.fcarcade.core.libretro.LibretroNesCore.PROFILE_SHA256;
        };
    }
    /** Selection is based on validated ROM metadata, never a user-provided core ordinal. */
    public static NesCoreVariant forRom(cn.piq.fcarcade.rom.INesHeader header,boolean gun) {
        java.util.Objects.requireNonNull(header,"ROM header");
        cn.piq.fcarcade.rom.NesCompatibility.requireSupported(header);
        if(header.mapper()==19&&gun)throw new IllegalArgumentException("Mapper 19 暂不支持光枪，请断开光枪后使用手柄。");
        return gun?LIBRETRO_ZAPPER_V1:LIBRETRO_V1;
    }
    /** Old keys remain byte-for-byte unchanged; the new ABI receives separate slots. */
    public String saveKey(String key) {return this==LEGACY?key:"core|"+stateNamespace()+"|"+key;}
    /** Cheap server identity gate; full bounded decompression stays in the owning core worker. */
    public boolean acceptsStateHeader(byte[] state,String romSha){
        if(state==null)return false;
        if(NesPersistentState.isBundle(state))return false;
        var b=java.nio.ByteBuffer.wrap(state);int magic=state.length>=4?b.getInt():0;
        if(this==LEGACY)return magic!=0x50515a31&&magic!=0x504E3139&&magic!=0x504C5231;
        if(isLibretro()) {
            if(state.length<81||magic!=0x504C5231||b.getInt()!=1||b.getInt()!=(isZapper()?1:0)
                    ||romSha==null||!romSha.matches("[0-9a-fA-F]{64}"))return false;
            byte[] profile=new byte[32],rom=new byte[32];b.get(profile);b.get(rom);
            if(!java.security.MessageDigest.isEqual(profile,java.util.HexFormat.of().parseHex(cn.piq.fcarcade.core.libretro.LibretroNesCore.PROFILE_SHA256))
                    ||!java.security.MessageDigest.isEqual(rom,java.util.HexFormat.of().parseHex(romSha)))return false;
            int size=b.getInt();return size>0&&size<=16*1024*1024&&size==b.remaining();
        }
        int expectedMagic=this==ZAPPER_V1?0x50515a31:0x504E3139;
        if(state.length<128||magic!=expectedMagic||b.getInt()!=1||romSha==null||!romSha.matches("[0-9a-fA-F]{64}"))return false;
        byte[] module=new byte[32],rom=new byte[32];b.get(module);b.get(rom);
        if(!java.security.MessageDigest.isEqual(module,java.util.HexFormat.of().parseHex(stateNamespace().substring(stateNamespace().indexOf('/')+1)))
                ||!java.security.MessageDigest.isEqual(rom,java.util.HexFormat.of().parseHex(romSha)))return false;
        b.position(104);int size=b.getInt();return size>0&&size<=64*1024*1024&&(size&65535)==0;
    }
    /** Stored/uploaded transaction only; live spectator replay still uses acceptsStateHeader. */
    public boolean acceptsPersistentStateHeader(byte[] state,String romSha){
        if(!NesPersistentState.isBundle(state))return acceptsStateHeader(state,romSha);
        if(!isLibretro())return false;
        try{return acceptsStateHeader(NesPersistentState.decode(state).snapshot(),romSha);}
        catch(IllegalArgumentException invalid){return false;}
    }
}
