// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.sfcarcade.core.SfcVideoMode;

/** Latest picture plus bounded accumulated stereo audio; skipping a rendered picture does not drop its sound. */
final class SfcCabinetFrames {
    static final int MAX_PCM=32768;
    private final short[] audio=new short[MAX_PCM];
    private int first,size;
    private CabinetFrame picture;
    synchronized void publish(SfcVideoMode mode,byte[] rgba,short[] pcm,int shorts){
        if(rgba.length!=mode.requiredRgbaBytes()||shorts<0||shorts>pcm.length||(shorts&1)!=0)
            throw new IllegalArgumentException("SFC output length mismatch");
        int[] abgr=new int[mode.width()*mode.height()];
        for(int y=0;y<mode.height();y++)for(int x=0;x<mode.width();x++){
            int p=y*mode.rowStrideBytes()+x*4;
            abgr[y*mode.width()+x]=0xff000000|(rgba[p]&255)|((rgba[p+1]&255)<<8)|((rgba[p+2]&255)<<16);
        }
        picture=new CabinetFrame(mode.width(),mode.height(),abgr,
                (float)(mode.width()*mode.pixelAspectRatio()/mode.height()),0,new short[0]);
        for(int i=0;i<shorts;i++){
            if(size==MAX_PCM){first=(first+1)%MAX_PCM;size--;}
            audio[(first+size)%MAX_PCM]=pcm[i];size++;
        }
    }
    synchronized CabinetFrame poll(){
        if(picture==null)return null;
        short[] pcm=new short[size];
        for(int i=0;i<size;i++)pcm[i]=audio[(first+i)%MAX_PCM];
        CabinetFrame p=picture;picture=null;first=0;size=0;
        return new CabinetFrame(p.width(),p.height(),p.abgr(),p.displayAspect(),0,pcm);
    }
    synchronized void clear(){picture=null;first=0;size=0;}
}
