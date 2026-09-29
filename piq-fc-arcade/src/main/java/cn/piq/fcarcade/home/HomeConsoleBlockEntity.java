package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class HomeConsoleBlockEntity extends HomeEndpointBlockEntity {
    private final HomeCartridgeSlot<ItemStack> cartridge = new HomeCartridgeSlot<>();
    private int controllerTakenMask;
    private final java.util.UUID[] controllerVisualPlayers=new java.util.UUID[2],controllerVisualLeases=new java.util.UUID[2];
    private boolean suborStructureInstalled;
    private boolean netplayExperimental;
    private boolean netplayJniTrial;
    public boolean netplayJniTrial(){return netplayExperimental&&netplayJniTrial;}
    public void netplayJniTrial(boolean value){netplayJniTrial=value;changed();}
    public boolean netplayExperimental(){return netplayExperimental;}
    public void netplayExperimental(boolean value){netplayExperimental=value;changed();}

    public HomeConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOME_CONSOLE.get(), pos, state);
    }

    @Override HomeLinkLedger.Kind kind() { return HomeLinkLedger.Kind.CONSOLE; }
    public BlockPos tvPos() { return peerPos(); }
    public boolean suborStructureInstalled() { return suborStructureInstalled; }
    void installSuborStructure() { suborStructureInstalled = true; changed(); }
    private ItemStack stored() { return cartridge.occupied() ? cartridge.value() : ItemStack.EMPTY; }
    public ItemStack insertedCartridge() { return stored().copy(); }
    public cn.piq.fcarcade.rom.RomSaveMode cartridgeSaveMode(cn.piq.fcarcade.rom.RomSaveMode legacy){
        if(level==null||level.isClientSide||!(level instanceof net.minecraft.server.level.ServerLevel server)||!server.getServer().isSameThread())throw new IllegalStateException("Server thread required");
        if(!hasCartridge())return cn.piq.fcarcade.rom.RomSaveMode.NONE;
        FcCartridgeData.ensureIdentity(stored());
        if(FcCartridgeData.storedSaveMode(stored())<0){FcCartridgeData.setSaveMode(stored(),legacy);changed();}
        return FcCartridgeData.saveMode(stored());
    }
    public void cartridgeSaved(java.util.UUID id,String rom,boolean saved){
        if(level==null||level.isClientSide||!(level instanceof net.minecraft.server.level.ServerLevel server)||!server.getServer().isSameThread())return;
        if(id!=null&&id.equals(FcCartridgeData.id(stored()))&&rom.equals(romSha())&&FcCartridgeData.hasSavedProgress(stored())!=saved){FcCartridgeData.setSavedProgress(stored(),saved);changed();}
    }
    /** 0 = P1, 1 = P2. Runtime leases, not world NBT, own these two docks. */
    public boolean controllerDocked(int port) { return port >= 0 && port < 2 && (controllerTakenMask & (1 << port)) == 0; }
    public java.util.UUID controllerVisualPlayer(int port){return port>=0&&port<2?controllerVisualPlayers[port]:null;}
    public java.util.UUID controllerVisualLease(int port){return port>=0&&port<2?controllerVisualLeases[port]:null;}
    /** Transient render identity, never authority or a persisted loan. */
    public void controllerVisual(int port,java.util.UUID player,java.util.UUID lease){
        if(port<0||port>1||level==null||level.isClientSide||!(level instanceof net.minecraft.server.level.ServerLevel server)||!server.getServer().isSameThread())return;
        if(player==null||lease==null){player=null;lease=null;}
        if(!java.util.Objects.equals(player,controllerVisualPlayers[port])||!java.util.Objects.equals(lease,controllerVisualLeases[port])){controllerVisualPlayers[port]=player;controllerVisualLeases[port]=lease;changed();}
    }
    void controllerTaken(int port, boolean taken) {
        if(port<0||port>1)return;
        boolean clear=!taken&&(controllerVisualPlayers[port]!=null||controllerVisualLeases[port]!=null);
        if(!taken){controllerVisualPlayers[port]=null;controllerVisualLeases[port]=null;}
        int next = taken ? controllerTakenMask | (1 << port) : controllerTakenMask & ~(1 << port);
        if (next != controllerTakenMask||clear) { controllerTakenMask = next; changed(); }
    }
    @Override public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) { controllerTakenMask = 0;java.util.Arrays.fill(controllerVisualPlayers,null);java.util.Arrays.fill(controllerVisualLeases,null);changed(); }
    }
    boolean hasCartridge() { return cartridge.occupied(); }
    String romSha() { return FcCartridgeData.romSha(stored()); }

    boolean insert(ItemStack stack) {
        if (!FcCartridgeData.isPlayable(stack) || stack.getCount() != 1 || !cartridge.insert(stack.copyWithCount(1))) return false;
        changed();
        return true;
    }

    ItemStack takeCartridge() {
        ItemStack result = cartridge.take();
        changed();
        return result == null ? ItemStack.EMPTY : result;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ItemStack parsed = ItemStack.parseOptional(registries, tag.getCompound("Cartridge"));
        cartridge.restore(FcCartridgeData.isPlayable(parsed) ? parsed.copyWithCount(1) : null);
        controllerTakenMask = tag.getInt("ControllerTakenMask") & 3;
        for(int port=0;port<2;port++){
            boolean pair=!controllerDocked(port)&&tag.hasUUID("ControllerVisualPlayer"+port)&&tag.hasUUID("ControllerVisualLease"+port);
            controllerVisualPlayers[port]=pair?tag.getUUID("ControllerVisualPlayer"+port):null;controllerVisualLeases[port]=pair?tag.getUUID("ControllerVisualLease"+port):null;
        }
        suborStructureInstalled = tag.getBoolean("WideSuborStructure");
        netplayExperimental = tag.getBoolean("NetplayExperimental");
        netplayJniTrial = tag.getBoolean("NetplayJniTrial");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Cartridge", stored().saveOptional(registries));
        tag.putInt("ControllerTakenMask", controllerTakenMask);
        tag.putBoolean("WideSuborStructure", suborStructureInstalled);
        tag.putBoolean("NetplayExperimental", netplayExperimental);
        tag.putBoolean("NetplayJniTrial", netplayJniTrial);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){
        var tag=super.getUpdateTag(registries);
        for(int port=0;port<2;port++)if(controllerVisualPlayers[port]!=null&&controllerVisualLeases[port]!=null){tag.putUUID("ControllerVisualPlayer"+port,controllerVisualPlayers[port]);tag.putUUID("ControllerVisualLease"+port,controllerVisualLeases[port]);}
        return tag;
    }
}
