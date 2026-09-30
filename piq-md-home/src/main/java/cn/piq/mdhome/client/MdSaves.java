// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import cn.piq.retro.libretro.LibretroSaveMemory;
import java.util.Arrays;

/** GX's documented trimmed battery format, used only before its first retro_run. */
public final class MdSaves {
    private MdSaves(){}
    public static byte[] startupRam(byte[] saved,LibretroSaveMemory fresh){
        int capacity=fresh.ram().length;
        if(fresh.rtc().length!=0||(capacity!=0&&capacity!=65536)||saved.length>capacity)
            throw new IllegalStateException("Genesis Plus GX 电池容量不匹配，原档保留");
        byte[] padded=new byte[capacity];Arrays.fill(padded,(byte)0xff);
        System.arraycopy(saved,0,padded,0,saved.length);return padded;
    }
}
