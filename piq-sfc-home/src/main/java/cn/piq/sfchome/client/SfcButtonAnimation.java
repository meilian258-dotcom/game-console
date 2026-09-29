// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import java.util.Set;

/** Read-only visual transforms for the supplied Blockbench button groups. */
final class SfcButtonAnimation {
    static final Set<String> KEYS=Set.of("button_a","button_b","button_x","button_y","dpad","select","start","shoulder_l","shoulder_r");
    record Binding(String key,float x,float y,float z,float press){}
    record Transform(float y,float pitch,float roll){static final Transform REST=new Transform(0,0,0);}
    private SfcButtonAnimation(){}
    static Transform sample(Binding binding,int mask){
        if(binding==null)return Transform.REST;
        mask&=0xfff;
        if(binding.key().equals("dpad")){
            int vertical=((mask>>4)&1)-((mask>>5)&1),horizontal=((mask>>7)&1)-((mask>>6)&1);
            return vertical==0&&horizontal==0?Transform.REST:new Transform(-binding.press()*.35f,vertical*5f,horizontal*5f);
        }
        int bit=switch(binding.key()){
            case "button_b"->0;case "button_y"->1;case "select"->2;case "start"->3;
            case "button_a"->8;case "button_x"->9;case "shoulder_l"->10;case "shoulder_r"->11;
            default->-1;
        };
        return bit>=0&&(mask&(1<<bit))!=0?new Transform(-binding.press(),0,0):Transform.REST;
    }
}
