// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.layout;

/** libretro rotation is counter-clockwise, not Minecraft block orientation. */
public final class NativeVideoPresentation {
    private NativeVideoPresentation(){}
    public static float displayAspect(float raw,int rotation){
        if(!Float.isFinite(raw)||raw<=0||raw>32)throw new IllegalArgumentException("Invalid native display aspect");
        return (Math.floorMod(rotation,4)&1)==0?raw:1/raw;
    }
    /** Inverse UV lookup from displayed top-left-origin coordinates into the raw texture. */
    public static float[] textureUv(float u,float v,int rotation){return switch(Math.floorMod(rotation,4)){
        case 1->new float[]{1-v,u};case 2->new float[]{1-u,1-v};case 3->new float[]{v,1-u};default->new float[]{u,v};};}
}
