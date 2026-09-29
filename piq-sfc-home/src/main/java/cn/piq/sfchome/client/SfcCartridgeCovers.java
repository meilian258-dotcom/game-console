// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.fcarcade.home.CartridgeCoverCodec;
import cn.piq.sfchome.net.SfcHomeNetwork;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Render-thread API; bounded network queue and off-thread validation/decode, no file I/O in rendering. */
final class SfcCartridgeCovers {
    private static final Map<String,ResourceLocation> TEXTURES=new LinkedHashMap<>(16,.75f,true);
    private static final Map<String,BlockPos> QUEUED=new LinkedHashMap<>();
    private static final Map<String,Long> RETRY=new LinkedHashMap<>();
    private static Object connection;
    private static String requested;
    private static byte[] incoming;
    private static int offset,generation;
    private static long started;
    private static boolean decoding;
    private SfcCartridgeCovers(){}
    static void initialize(){
        SfcHomeNetwork.setCoverHandler(SfcCartridgeCovers::receive);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)->tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->clear());
    }
    static ResourceLocation texture(String hash,BlockPos console){
        if(hash.isEmpty())return null;
        var mc=Minecraft.getInstance();ResourceLocation result=TEXTURES.get(hash);
        if(result==null&&mc.getConnection()!=null&&QUEUED.size()<32&&!hash.equals(requested)&&System.nanoTime()>=RETRY.getOrDefault(hash,Long.MIN_VALUE))
            QUEUED.putIfAbsent(hash,console==null?(mc.player==null?BlockPos.ZERO:mc.player.blockPosition()):console.immutable());
        return result;
    }
    private static void tick(){
        var mc=Minecraft.getInstance();if(mc.getConnection()!=connection){clear();connection=mc.getConnection();}
        if(connection==null)return;
        if(requested!=null&&System.nanoTime()-started>20_000_000_000L)fail(requested);
        if(requested!=null||QUEUED.isEmpty())return;
        var it=QUEUED.entrySet().iterator();var entry=it.next();it.remove();
        if(TEXTURES.containsKey(entry.getKey())||System.nanoTime()<RETRY.getOrDefault(entry.getKey(),Long.MIN_VALUE))return;
        requested=entry.getKey();started=System.nanoTime();incoming=null;offset=0;decoding=false;
        PacketDistributor.sendToServer(new SfcHomeNetwork.CoverRequest(requested,entry.getValue()));
    }
    private static void receive(SfcHomeNetwork.CoverChunk chunk){
        var mc=Minecraft.getInstance();if(connection==null||connection!=mc.getConnection()||requested==null||!requested.equals(chunk.coverSha())||decoding)return;
        byte[] part=chunk.data();
        if(incoming==null){if(chunk.offset()!=0){fail(requested);return;}incoming=new byte[chunk.total()];}
        if(chunk.total()!=incoming.length||chunk.offset()!=offset||(long)offset+part.length>incoming.length){fail(requested);return;}
        System.arraycopy(part,0,incoming,offset,part.length);offset+=part.length;
        if(offset!=incoming.length)return;
        byte[] png=incoming;incoming=null;decoding=true;String hash=requested;int epoch=generation;Object owner=connection;
        if(!LocalRomLibrary.submit(()->{
            NativeImage decoded=null;
            try{CartridgeCoverCodec.validate(png,hash);decoded=NativeImage.read(new ByteArrayInputStream(png));NativeImage image=decoded;
                mc.execute(()->{
                    if(epoch!=generation||connection!=owner||mc.getConnection()!=owner||!hash.equals(requested)){image.close();return;}
                    DynamicTexture texture=null;
                    try{
                        if(image.getWidth()!=512||image.getHeight()!=256)throw new IllegalArgumentException("Cover size");
                        if(TEXTURES.size()>=16){var it=TEXTURES.entrySet().iterator();var oldest=it.next();it.remove();mc.getTextureManager().release(oldest.getValue());defer(oldest.getKey());}
                        var location=ResourceLocation.fromNamespaceAndPath("piq_sfc_home","dynamic/cover/"+hash);
                        texture=new DynamicTexture(image);mc.getTextureManager().register(location,texture);TEXTURES.put(hash,location);requested=null;decoding=false;
                    }catch(RuntimeException error){if(texture!=null)texture.close();else image.close();fail(hash);}
                });
            }catch(Exception error){if(decoded!=null)decoded.close();mc.execute(()->{if(epoch==generation&&hash.equals(requested))fail(hash);});}
        }))fail(hash);
    }
    private static void defer(String hash){if(RETRY.size()>=128&&!RETRY.containsKey(hash))RETRY.remove(RETRY.keySet().iterator().next());RETRY.put(hash,System.nanoTime()+15_000_000_000L);}
    private static void fail(String hash){defer(hash);if(hash.equals(requested)){requested=null;incoming=null;decoding=false;}}
    private static void clear(){generation++;requested=null;incoming=null;decoding=false;QUEUED.clear();RETRY.clear();var mc=Minecraft.getInstance();for(var id:TEXTURES.values())mc.getTextureManager().release(id);TEXTURES.clear();connection=null;}
}
