// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

public final class GbaProtocol {
    private GbaProtocol(){}
    public static final int MAGIC=0x50475147,VERSION=1,STEP=1,SAVE=2,CLOSE=3;
    public static final int MAX_PCM=32768,MAX_SAVE=131072;
    public static boolean saveSize(int n){return n==512||n==8192||n==32768||n==65536||n==131072;}
}
