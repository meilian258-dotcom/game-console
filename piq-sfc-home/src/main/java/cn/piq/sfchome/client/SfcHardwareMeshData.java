// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU-only bounded parser; geometry is prepared once per resource reload, never per frame. */
final class SfcHardwareMeshData {
    static final Set<String> GROUPS=Set.of("body","p1_docked","p2_docked","slot_cover","inserted","controller","cartridge");
    static final int MAX_CHARS=12_000_000,MAX_TRIANGLES=18000,MAX_PARTS=1000;
    record Part(String name,String texture,float[] vertices,SfcButtonAnimation.Binding binding){}
    private SfcHardwareMeshData(){}
    static Map<String,List<Part>> read(Reader reader)throws IOException{
        StringBuilder text=new StringBuilder();char[] chunk=new char[8192];int count;
        while((count=reader.read(chunk))!=-1){if(text.length()+count>MAX_CHARS)throw new IOException("SFC mesh resource too large");text.append(chunk,0,count);}
        JsonObject root=JsonParser.parseString(text.toString()).getAsJsonObject();
        if(root.get("version").getAsInt()!=1)throw new IllegalArgumentException("Unsupported SFC mesh version");
        JsonObject materials=root.getAsJsonObject("materials"),groups=root.getAsJsonObject("groups");
        if(materials.size()<1||materials.size()>16||!groups.keySet().equals(GROUPS))throw new IllegalArgumentException("SFC mesh group/material count");
        Map<String,String> textures=new HashMap<>();
        for(var entry:materials.entrySet()){
            String resource=entry.getValue().getAsString();
            if(!resource.matches("(?:minecraft|piq_fc_arcade|piq_sfc_home):block/[a-z0-9_/]+")||resource.contains(".."))throw new IllegalArgumentException("SFC material resource");
            textures.put(entry.getKey(),resource.replace(":block/",":textures/block/")+".png");
        }
        var result=new HashMap<String,List<Part>>();
        for(String name:GROUPS){
            JsonArray parts=groups.getAsJsonObject(name).getAsJsonArray("parts");
            if((parts.isEmpty()&&!name.equals("slot_cover"))||parts.size()>MAX_PARTS)throw new IllegalArgumentException("SFC mesh part count");
            List<Part> data=new ArrayList<>();int groupTriangles=0;
            for(var entry:parts){
                JsonObject part=entry.getAsJsonObject();String label=part.get("name").getAsString();
                String texture=textures.get(part.get("material").getAsString());
                JsonArray triangles=part.getAsJsonArray("triangles");groupTriangles+=triangles.size();
                if(label.isBlank()||label.length()>120||texture==null||triangles.isEmpty()||groupTriangles>MAX_TRIANGLES)throw new IllegalArgumentException("SFC mesh part");
                float[] vertices=new float[triangles.size()*24];int at=0;
                for(var triEntry:triangles){
                    JsonObject tri=triEntry.getAsJsonObject();JsonArray p=tri.getAsJsonArray("p"),uv=tri.getAsJsonArray("uv"),normal=tri.getAsJsonArray("n");
                    if(p.size()!=3||uv.size()!=3||normal.size()!=3)throw new IllegalArgumentException("SFC mesh triangle shape");
                    float nx=number(normal,0,-1,1),ny=number(normal,1,-1,1),nz=number(normal,2,-1,1);
                    if(Math.abs(nx*nx+ny*ny+nz*nz-1)>.0001)throw new IllegalArgumentException("SFC mesh unit normal");
                    for(int v=0;v<3;v++){
                        JsonArray position=p.get(v).getAsJsonArray(),textureUv=uv.get(v).getAsJsonArray();
                        if(position.size()!=3||textureUv.size()!=2)throw new IllegalArgumentException("SFC mesh vertex shape");
                        for(int axis=0;axis<3;axis++)vertices[at++]=number(position,axis,0,16)/16;
                        vertices[at++]=number(textureUv,0,0,1);vertices[at++]=number(textureUv,1,0,1);
                        vertices[at++]=nx;vertices[at++]=ny;vertices[at++]=nz;
                    }
                }
                SfcButtonAnimation.Binding binding=null;
                if(part.has("motion")){
                    String key=part.get("motion").getAsString();JsonArray pivot=part.getAsJsonArray("pivot");
                    float press=part.get("press").getAsFloat();
                    if(!SfcButtonAnimation.KEYS.contains(key)||pivot.size()!=3||!Float.isFinite(press)||press<=0||press>.2f
                            ||!(name.equals("controller")||name.equals("p1_docked")||name.equals("p2_docked")))throw new IllegalArgumentException("SFC button binding");
                    binding=new SfcButtonAnimation.Binding(key,number(pivot,0,0,16)/16,number(pivot,1,0,16)/16,number(pivot,2,0,16)/16,press/16);
                }
                data.add(new Part(label,texture,vertices,binding));
            }
            result.put(name,List.copyOf(data));
        }
        return Map.copyOf(result);
    }
    private static float number(JsonArray array,int at,float minimum,float maximum){
        float value=array.get(at).getAsFloat();
        if(!Float.isFinite(value)||value<minimum||value>maximum)throw new IllegalArgumentException("SFC mesh finite bounds");
        return value;
    }
}
