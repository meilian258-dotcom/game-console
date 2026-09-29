package cn.piq.sfchome.data;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Content-addressed cartridge identity. Missing player metadata remains a legacy card. */
public final class SfcCartridgeData {
    private static final String KEY="piq_sfc_home_cartridge";
    private SfcCartridgeData(){}
    public static boolean isCartridge(ItemStack stack){return stack!=null&&stack.is(SfcHomeRegistries.CARTRIDGE.get());}
    private static CompoundTag tag(ItemStack stack){var d=stack.get(DataComponents.CUSTOM_DATA);return d==null?new CompoundTag():d.copyTag().getCompound(KEY);}
    public static String romSha(ItemStack stack){String h=tag(stack).getString("Rom");return h.matches("[0-9a-f]{64}")?h:"";}
    public static String coverSha(ItemStack stack){String h=tag(stack).getString("Cover");return h.matches("[0-9a-f]{64}")?h:"";}
    public static boolean hasExplicitPlayerCount(ItemStack stack){return tag(stack).contains("MaxPlayers",3);}
    public static int maxPlayers(ItemStack stack){return tag(stack).getInt("MaxPlayers")==1?1:2;}
    public static String title(ItemStack stack){String t=tag(stack).getString("Title");return t.length()<=128?t:"";}
    public static UUID id(ItemStack stack){var t=tag(stack);return t.hasUUID("Id")?t.getUUID("Id"):null;}
    /** Netplay only: 0 disabled, 1 personal by opening player + ROM, 2 physical cartridge + ROM. */
    public static int saveMode(ItemStack stack){var t=tag(stack);return t.contains("SaveMode",3)?t.getInt("SaveMode"):2;}
    public static void setSaveMode(ItemStack stack,int mode){if(!supported(stack)||mode<0||mode>2)throw new IllegalArgumentException("Invalid save mode");ensureId(stack);var root=stack.get(DataComponents.CUSTOM_DATA).copyTag();root.getCompound(KEY).putInt("SaveMode",mode);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(root));}
    public static boolean supported(ItemStack stack){
        if(!isCartridge(stack)||stack.getCount()!=1)return false;
        for(var e:stack.getComponentsPatch().entrySet())if(e.getKey()!=DataComponents.CUSTOM_DATA||e.getValue().isEmpty())return false;
        var d=stack.get(DataComponents.CUSTOM_DATA);if(d==null)return true;var root=d.copyTag();
        if(!root.getAllKeys().equals(Set.of(KEY))||!root.contains(KEY,10))return false;
        var t=root.getCompound(KEY);if(!Set.of("Rom","Title","Id","Cover","MaxPlayers","SaveMode").containsAll(t.getAllKeys()))return false;
        return (!t.contains("Rom")||t.contains("Rom",8)&&t.getString("Rom").matches("[0-9a-f]{64}"))
            &&(!t.contains("Title")||t.contains("Title",8)&&t.getString("Title").length()<=128&&!t.getString("Title").chars().anyMatch(Character::isISOControl))
            &&(!t.contains("Id")||t.hasUUID("Id"))
            &&(!t.contains("Cover")||t.contains("Cover",8)&&t.getString("Cover").matches("[0-9a-f]{64}"))
            &&(!t.contains("MaxPlayers")||t.contains("MaxPlayers",3)&&(t.getInt("MaxPlayers")==1||t.getInt("MaxPlayers")==2))
            &&(!t.contains("SaveMode")||t.contains("SaveMode",3)&&t.getInt("SaveMode")>=0&&t.getInt("SaveMode")<=2);
    }
    public static void ensureId(ItemStack stack){if(!supported(stack))throw new IllegalArgumentException("Unsupported cartridge metadata");if(id(stack)==null)write(stack,romSha(stack),title(stack));}
    public static void write(ItemStack stack,String hash,String title){
        writeMetadata(stack,hash,title,coverSha(stack),hasExplicitPlayerCount(stack)?maxPlayers(stack):0);
    }
    public static void write(ItemStack stack,String hash,String title,String cover,int maxPlayers){
        if(maxPlayers!=1&&maxPlayers!=2)throw new IllegalArgumentException("Invalid player count");
        writeMetadata(stack,hash,title,cover,maxPlayers);
    }
    public static void setCover(ItemStack stack,String cover){writeMetadata(stack,romSha(stack),title(stack),cover,hasExplicitPlayerCount(stack)?maxPlayers(stack):0);}
    public static void setPlayers(ItemStack stack,int players){write(stack,romSha(stack),title(stack),coverSha(stack),players);}
    private static void writeMetadata(ItemStack stack,String hash,String title,String cover,int players){
        if(!supported(stack)||hash==null||!hash.isEmpty()&&!hash.matches("[0-9a-f]{64}")||cover==null||!cover.isEmpty()&&!cover.matches("[0-9a-f]{64}")||title==null||title.length()>128||title.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid cartridge");
        UUID id=id(stack);int save=saveMode(stack);var t=new CompoundTag();t.putInt("SaveMode",save);t.putUUID("Id",id==null?UUID.randomUUID():id);
        if(!hash.isEmpty())t.putString("Rom",hash);if(!title.isEmpty())t.putString("Title",title);
        if(!cover.isEmpty())t.putString("Cover",cover);if(players!=0)t.putInt("MaxPlayers",players);
        var root=new CompoundTag();root.put(KEY,t);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(root));
    }
}
