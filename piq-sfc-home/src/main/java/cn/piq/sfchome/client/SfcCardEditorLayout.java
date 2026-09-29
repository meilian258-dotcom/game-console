// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

record SfcCardEditorLayout(int x,int y,int width,int height,int rows,boolean supported) {
    static SfcCardEditorLayout of(int width,int height){
        int w=Math.min(550,Math.max(0,width-16)),h=Math.min(410,Math.max(0,height-16));
        return new SfcCardEditorLayout((width-w)/2,(height-h)/2,w,h,Math.max(1,(h-198)/22),width>=320&&height>=240);
    }
    int listY(){return y+125;}
    int pageY(){return y+height-70;}
    int actionY(){return y+height-48;}
    int statusY(){return y+height-23;}
}
