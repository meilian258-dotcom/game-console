package cn.piq.sfchome.world;
import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import cn.piq.sfchome.data.SfcCartridgeData;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;
import java.util.Objects;

public final class SfcHomeConsoleBlockEntity extends ExternalHomeConsoleBlockEntity {
    public static final ResourceLocation SYSTEM_ID=ResourceLocation.fromNamespaceAndPath("piq_sfc_home","sfc");
    private ItemStack cartridge=ItemStack.EMPTY;
    private int leasedMask;
    private boolean netplay;
    @Override public boolean netplayExperimental(){return netplay;}
    @Override public void netplayExperimental(boolean value){if(netplay!=value){netplay=value;notifyHardwareChanged();}}
    private final UUID[] controllerPlayers=new UUID[2],controllerLeases=new UUID[2];
    public SfcHomeConsoleBlockEntity(BlockPos pos,BlockState state){super(SfcHomeRegistries.CONSOLE_ENTITY.get(),pos,state,SYSTEM_ID);}
    public boolean hasCartridge(){return !cartridge.isEmpty();}
    @Override public boolean hasInsertedCartridge(){return hasCartridge();}
    public ItemStack insertedCartridge(){return cartridge.copy();}
    public boolean controllerDocked(int port){return port<0||port>1||(leasedMask&(1<<port))==0;}
    /** Visual receipts only; server session authority never trusts block-entity metadata. */
    public UUID controllerVisualPlayer(int port){return controllerDocked(port)?null:controllerPlayers[port];}
    public UUID controllerVisualLease(int port){return controllerDocked(port)?null:controllerLeases[port];}
    public String romSha(){return SfcCartridgeData.romSha(cartridge);}
    public String gameTitle(){return SfcCartridgeData.title(cartridge);}
    /** Server service alone owns insert/eject and lease transactions. Never exposes the live stack. */
    public boolean insert(ItemStack input){if(level==null||level.isClientSide||!level.getServer().isSameThread()||hasCartridge()||!SfcCartridgeData.supported(input))return false;cartridge=input.copy();cartridge.setCount(1);notifyHardwareChanged();return true;}
    public ItemStack eject(){if(level==null||level.isClientSide||!level.getServer().isSameThread())return ItemStack.EMPTY;ItemStack out=cartridge;cartridge=ItemStack.EMPTY;notifyHardwareChanged();return out;}
    public void setControllerVisual(int port,UUID player,UUID lease){
        if(port<0||port>1||level==null||level.isClientSide||!level.getServer().isSameThread())return;
        if(player==null||lease==null){player=null;lease=null;}
        int next=lease==null?leasedMask&~(1<<port):leasedMask|(1<<port);
        if(next!=leasedMask||!Objects.equals(controllerPlayers[port],player)||!Objects.equals(controllerLeases[port],lease)){
            leasedMask=next;controllerPlayers[port]=player;controllerLeases[port]=lease;notifyHardwareChanged();
        }
    }
    public void setControllerLeased(int port,boolean leased){if(!leased){setControllerVisual(port,null,null);return;}if(port<0||port>1||level==null||level.isClientSide||!level.getServer().isSameThread())return;int next=leasedMask|(1<<port);if(next!=leasedMask){leasedMask=next;notifyHardwareChanged();}}
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide){leasedMask=0;java.util.Arrays.fill(controllerPlayers,null);java.util.Arrays.fill(controllerLeases,null);notifyHardwareChanged();}}
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider p){super.saveAdditional(t,p);t.putBoolean("SfcNetplayExperimental",netplay);if(!cartridge.isEmpty())t.put("SfcCartridge",cartridge.save(p));t.putInt("SfcLeasedMask",leasedMask);for(int port=0;port<2;port++){if(controllerVisualPlayer(port)!=null&&controllerVisualLease(port)!=null){t.putUUID("SfcControllerPlayer"+port,controllerPlayers[port]);t.putUUID("SfcControllerLease"+port,controllerLeases[port]);}else{t.remove("SfcControllerPlayer"+port);t.remove("SfcControllerLease"+port);}}}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider p){super.loadAdditional(t,p);netplay=t.getBoolean("SfcNetplayExperimental");cartridge=t.contains("SfcCartridge",10)?ItemStack.parseOptional(p,t.getCompound("SfcCartridge")):ItemStack.EMPTY;leasedMask=t.getInt("SfcLeasedMask")&3;for(int port=0;port<2;port++){controllerPlayers[port]=null;controllerLeases[port]=null;if(!controllerDocked(port)&&t.hasUUID("SfcControllerPlayer"+port)&&t.hasUUID("SfcControllerLease"+port)){controllerPlayers[port]=t.getUUID("SfcControllerPlayer"+port);controllerLeases[port]=t.getUUID("SfcControllerLease"+port);}}}
}
