package cn.piq.sfchome.client;

import cn.piq.sfchome.data.SfcCartridgeData;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import cn.piq.sfchome.server.SfcCoverStore;
import cn.piq.fcarcade.home.CartridgeCoverCodec;
import cn.piq.fcarcade.rom.RomRepository;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.client.resources.model.BakedModel;
import com.mojang.blaze3d.vertex.PoseStack;
import io.netty.buffer.Unpooled;

/** Actual production classes, actual MC ItemStack and packet codecs. No client/game process. */
public final class SfcCardMetadataProbe {
    static int checks;
    static final String ROM="a".repeat(64),COVER="b".repeat(64);
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void fails(Runnable action,String why){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,why);}
    interface Io{void run()throws Exception;}
    static void ioFails(Io action,String why)throws Exception{boolean rejected=false;try{action.run();}catch(IOException e){rejected=true;}check(rejected,why);}
    static void field(Object object,String name,Object value)throws Exception{for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);return;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    static <T>T roundtrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{codec.encode(b,value);T decoded=codec.decode(b);check(b.readableBytes()==0,"codec consumes exactly one message");return decoded;}finally{b.release();}}
    public static void main(String[]args)throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        field(SfcHomeRegistries.CARTRIDGE,"holder",Holder.direct(Items.PAPER));
        var card=new ItemStack(Items.PAPER);check(SfcCartridgeData.supported(card),"old untagged card accepted");
        SfcCartridgeData.ensureId(card);UUID id=SfcCartridgeData.id(card);check(id!=null,"identity allocated");
        check(!SfcCartridgeData.hasExplicitPlayerCount(card)&&SfcCartridgeData.maxPlayers(card)==2,"legacy capacity does not imply waiting");
        SfcCartridgeData.write(card,ROM,"旧卡");check(!SfcCartridgeData.hasExplicitPlayerCount(card),"old write leaves legacy marker absent");
        SfcCartridgeData.setCover(card,COVER);check(SfcCartridgeData.romSha(card).equals(ROM)&&SfcCartridgeData.title(card).equals("旧卡"),"cover preserves ROM and name");
        SfcCartridgeData.setPlayers(card,1);check(SfcCartridgeData.hasExplicitPlayerCount(card)&&SfcCartridgeData.maxPlayers(card)==1,"explicit solo persists");
        SfcCartridgeData.write(card,ROM,"改名");check(SfcCartridgeData.coverSha(card).equals(COVER)&&SfcCartridgeData.maxPlayers(card)==1,"rename preserves cover and players");
        SfcCartridgeData.setPlayers(card,2);check(SfcCartridgeData.maxPlayers(card)==2,"explicit duo persists");
        var saved=card.copy();
        for(String bad:new String[]{"bad\nname","\u0000","x".repeat(129)}){fails(()->SfcCartridgeData.write(card,ROM,bad),"bad title rejected");check(ItemStack.isSameItemSameComponents(card,saved),"bad title leaves all components unchanged");}
        for(int bad:new int[]{-1,0,3,255}){fails(()->SfcCartridgeData.setPlayers(card,bad),"bad players rejected");check(ItemStack.isSameItemSameComponents(card,saved),"bad players leave old card unchanged");}
        fails(()->SfcCartridgeData.setCover(card,"../escape"),"bad cover hash rejected");check(ItemStack.isSameItemSameComponents(card,saved),"bad cover leaves old card unchanged");
        SfcCartridgeData.setCover(card,"");check(SfcCartridgeData.coverSha(card).isEmpty()&&id.equals(SfcCartridgeData.id(card)),"clear cover preserves UUID");
        SfcCartridgeData.setCover(card,COVER);check(ItemStack.isSameItemSameComponents(card,saved),"restore cover restores exact components");
        var registry=RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var reloaded=ItemStack.parseOptional(registry,(net.minecraft.nbt.CompoundTag)card.save(registry));check(ItemStack.isSameItemSameComponents(card,reloaded),"save/load survives metadata");
        for(int kind=0;kind<4;kind++){
            var broken=card.copy();var root=broken.get(DataComponents.CUSTOM_DATA).copyTag();var tag=root.getCompound("piq_sfc_home_cartridge");
            switch(kind){case 0->tag.putInt("MaxPlayers",3);case 1->tag.putByte("MaxPlayers",(byte)1);case 2->tag.putString("Cover","bad");case 3->tag.putString("extra","no");}
            broken.set(DataComponents.CUSTOM_DATA,CustomData.of(root));check(!SfcCartridgeData.supported(broken),"malformed metadata rejected kind="+kind);
        }
        check(!SfcCartridgeData.supported(card.copyWithCount(2)),"stacked cards remain rejected");
        var token=UUID.randomUUID();var entry=new SfcHomeNetwork.RomEntry(ROM,"demo.sfc",32768);
        var editor=new SfcHomeNetwork.Editor(token,true,"ready",ROM,"卡带",COVER,2,true,List.of(entry));
        check(editor.equals(roundtrip(SfcHomeNetwork.Editor.CODEC,editor)),"editor metadata roundtrip");
        for(int operation:new int[]{SfcHomeNetwork.SAVE_NAME,SfcHomeNetwork.COVER_START,SfcHomeNetwork.CLEAR_COVER,SfcHomeNetwork.RESTORE_COVER,SfcHomeNetwork.SET_PLAYERS}){
            var action=new SfcHomeNetwork.EditorAction(token,operation,ROM,"新名称",2,0,new byte[]{1,2});var decoded=roundtrip(SfcHomeNetwork.EditorAction.CODEC,action);
            check(decoded.operation()==operation&&decoded.name().equals("新名称")&&Arrays.equals(decoded.data(),new byte[]{1,2}),"action roundtrip "+operation);
        }
        fails(()->new SfcHomeNetwork.EditorAction(token,6,ROM,"bad\nname",0,0,new byte[0]),"control character denied at packet construction");
        fails(()->new SfcHomeNetwork.Editor(token,false,"",ROM,"",COVER,3,true,List.of()),"bad reply player count denied");
        var request=new SfcHomeNetwork.CoverRequest(COVER,new BlockPos(1,2,3));check(request.equals(roundtrip(SfcHomeNetwork.CoverRequest.CODEC,request)),"cover endpoint roundtrip");
        var chunk=new SfcHomeNetwork.CoverChunk(COVER,100,0,new byte[]{1,2,3});var cd=roundtrip(SfcHomeNetwork.CoverChunk.CODEC,chunk);check(cd.total()==100&&Arrays.equals(cd.data(),chunk.data()),"cover chunk roundtrip");
        byte[] changed=chunk.data();changed[0]=7;check(chunk.data()[0]==1,"cover chunk defensively copies");
        fails(()->new SfcHomeNetwork.CoverChunk(COVER,SfcHomeNetwork.MAX_COVER+1,0,new byte[]{1}),"oversized cover rejected before allocation");
        fails(()->new SfcHomeNetwork.CoverChunk(COVER,100,99,new byte[]{1,2}),"overflow chunk rejected");
        Path temp=Path.of(args[0]);var source=new BufferedImage(512,256,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<256;y++)for(int x=0;x<512;x++)source.setRGB(x,y,(x<256?0xFF0000:0x00FF00)|(y<128?0x000080:0x000030));
        var output=new ByteArrayOutputStream();ImageIO.write(source,"png",output);byte[] png=CartridgeCoverCodec.prepare(output.toByteArray());String hash=RomRepository.sha256(png);
        var store=new SfcCoverStore(temp.resolve("store/covers"));store.store(hash,png);check(Arrays.equals(png,store.read(hash)),"actual FC codec + SFC store roundtrip");
        store.store(hash,png);try(var files=Files.list(temp.resolve("store/covers"))){check(files.count()==1,"same cover store is idempotent");}
        ioFails(()->store.store(COVER,png),"bad SHA upload rejected");ioFails(()->store.store(hash,new byte[40]),"invalid PNG upload rejected");
        var blocker=temp.resolve("ordinary-file");Files.writeString(blocker,"do not modify");ioFails(()->new SfcCoverStore(blocker.resolve("covers")).store(hash,png),"file parent rejected");check(Files.readString(blocker).equals("do not modify"),"rejected parent unchanged");
        var f=SfcCoverGeometry.label(false);var g=SfcCoverGeometry.label(true);
        check(f.left()>4.51/16&&f.right()<11.49/16&&f.z()<7.245/16,"item overlay inside existing label");
        check(Math.abs((f.right()-f.left())/(f.top()-f.bottom())-2)<1e-6,"item cover aspect 2:1");
        check(Math.abs((g.right()-g.left())/(g.top()-g.bottom())-2)<1e-6,"inserted cover aspect 2:1");
        check(Math.abs(g.left()-((f.left()*16-8)*.645+8)/16)<1e-7,"inserted model scale matches");
        final int[] delegated={0};BakedModel parent=(BakedModel)Proxy.newProxyInstance(BakedModel.class.getClassLoader(),new Class<?>[]{BakedModel.class},(proxy,m,a)->{if(m.getName().equals("applyTransform")){delegated[0]++;return proxy;}if(m.getReturnType()==boolean.class)return false;return null;});
        var type=Class.forName("cn.piq.sfchome.client.SfcCartridgeRenderer$ItemModel");var constructor=type.getDeclaredConstructor(BakedModel.class);constructor.setAccessible(true);BakedModel wrapper=(BakedModel)constructor.newInstance(parent);
        check(wrapper.isCustomRenderer(),"existing item opts into label renderer");check(wrapper.applyTransform(ItemDisplayContext.GUI,new PoseStack(),false)==wrapper&&delegated[0]==1,"existing display transform applied exactly once and wrapper retained");
        check(wrapper.getRenderPasses(card,false).equals(List.of(wrapper)),"render passes retain custom renderer");
        System.out.println("SFC_METADATA_REAL_CLASSES_CHECKS="+checks+" PASSED; no live-world or visual claim");
    }
}
