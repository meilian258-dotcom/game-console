package cn.piq.fcarcade.fixtures;

/** Original NROM fixture, no game ROM assets. Read SRAM into CPU $00, write $5a, loop. */
public final class NativeSaveTestRom {
    private NativeSaveTestRom() {}
    public static byte[] bytes() {
        byte[] rom = new byte[16 + 16384 + 8192];
        rom[0]='N'; rom[1]='E'; rom[2]='S'; rom[3]=26; rom[4]=1; rom[5]=1; rom[6]=2; rom[8]=1;
        int[] code={0x78,0xd8,0xa2,0xff,0x9a,0xad,0x00,0x60,0x85,0x00,0xa9,0x5a,0x8d,0x00,0x60,0x4c,0x0f,0x80};
        for(int i=0;i<code.length;i++)rom[16+i]=(byte)code[i];
        for(int i=0x3ffa;i<0x4000;i+=2)rom[16+i+1]=(byte)0x80;
        return rom;
    }
    public static String sha() {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes())); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
