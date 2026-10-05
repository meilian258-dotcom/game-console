// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import java.util.regex.Pattern;

/** UI labels only. Never substitutes a ROM identity, path or network filename. */
final class SfcWorkbenchDisplay {
    private static final Pattern HASH_NAME=Pattern.compile("(?i)[0-9a-f]{64}(?:\\.(?:sfc|smc))?");
    private SfcWorkbenchDisplay(){}
    static String name(SfcCardLibrary.Row row,String currentRom,String currentTitle){
        if(!row.local()&&row.hash().equals(currentRom)&&currentTitle!=null&&!currentTitle.isBlank())return currentTitle;
        String original=row.name();
        if(!row.local()&&HASH_NAME.matcher(original).matches())return "未命名游戏 · "+original.substring(0,8);
        return original;
    }
    static String current(String title){return title==null||title.isBlank()?"未命名卡带":title;}
    /** The server resolves missing legacy metadata. Explicitness is not a single-player override. */
    static String players(int maximum){return "人数："+(maximum==2?"双人":"单人");}
    static int nextPlayers(int maximum){return maximum==2?1:2;}
    static String source(SfcCardLibrary.Row row){return row==null?"未选择":row.local()?"本地待上传":"服务器库";}
    static String[] details(String heading,String name,String source,int lines){
        if(lines<=0)return new String[0];
        if(lines==1)return new String[]{heading+" · "+name};
        if(lines==2)return new String[]{heading+" · "+name,"来源："+source};
        return new String[]{heading,"名称："+name,"来源："+source};
    }
    static String original(SfcCardLibrary.Row row){return "原文件名："+row.name()+(row.local()?"\n"+row.path():"\nSHA-256："+row.hash());}
}
