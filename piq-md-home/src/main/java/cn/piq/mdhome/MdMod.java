// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.home.content.*;
import cn.piq.fcarcade.registry.ModCreativeTabs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.*;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(MdMod.ID)
public final class MdMod {
    public static final String ID="piq_md_home";
    public static final ResourceLocation SYSTEM=ResourceLocation.fromNamespaceAndPath(ID,"md");
    public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(ID);
    public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,ID);
    public static final DeferredBlock<MdBlock> CONSOLE=BLOCKS.register("md2",()->new MdBlock(BlockBehaviour.Properties.of().strength(1.5f).noOcclusion()));
    public static final DeferredItem<BlockItem> CONSOLE_ITEM=ITEMS.registerSimpleBlockItem("md2",CONSOLE);
    public static final DeferredItem<Item> CARTRIDGE=ITEMS.register("md_cartridge",()->new ContentCartridgeItem(new Item.Properties().stacksTo(1),SYSTEM));
    public static final DeferredItem<Item> CONTROLLER=ITEMS.register("md_controller",()->new MdController(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<MdConsole>> ENTITY=ENTITIES.register("md2",()->BlockEntityType.Builder.of(MdConsole::new,CONSOLE.get()).build(null));
    public MdMod(IEventBus bus){
        BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MdController::tossed);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MdController::drops);
        ContentCards.register(SYSTEM,new ContentCards.Adapter(CARTRIDGE,"MD2",java.util.Set.of("md","bin","gen"),cn.piq.mdhome.client.MdRom::validate));
        ContentCards.features(SYSTEM,new ContentCards.Features(true,true,true,2));
        cn.piq.mdhome.save.MdPublicSaves.install();
        bus.addListener(MdPublicNetwork::register);MdPublicServer.register();
        HomeApplianceService.registerControls(SYSTEM,MdControls::pick);
        HomeSystems.register(SYSTEM,new HomeSystems.ServerHooks(){
            public boolean deviceSettingsAvailable(){return true;}
            public boolean occupancyDisplaySupported(){return true;}
            public boolean synchronizationSettingsAvailable(){return true;}
            public boolean jniNetplaySettingsAvailable(){return MdNetplayProfile.AVAILABLE;}
            public int synchronizationSupportedModes(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){return MdNetplayProfile.AVAILABLE?1|16:1;}
            public boolean synchronizationSettingsBusy(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){return ((MdConsole)c).busy();}
            public boolean pendingStart(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){return cn.piq.mdhome.save.MdPublicSaves.pending(l.getServer(),c.hardwareId())||MdPrivateServer.pending((MdConsole)c);}
            public String synchronizationUnavailableReason(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c,cn.piq.fcarcade.cabinet.CabinetSyncMode mode){return "MD 尚未适配此运行方式；JNI Netplay 使用独立的验证与设置项。";}
            public String synchronizationUnavailableReason(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c,int mode){return mode==4?MdNetplayProfile.AVAILABLE?"服务器尚未允许本地同步 / JNI Netplay":MdNetplayProfile.UNAVAILABLE:"MD 尚未适配此运行方式；可使用玩家音画串流";}
            public String deviceSettingsStatus(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){
                var md=(MdConsole)c;
                return (md.running()?(md.publicPlay()?(md.netplayJniTrial()?"公开 JNI Netplay":"公开玩家串流 · JNI"):"私人单人")+" · 运行中":md.busy()?"正在准备或结束保存":"关机 · 下次 "+(md.netplayJniTrial()?"JNI Netplay":"JNI 串流"))+" · "+(md.televisionPos()==null?"未接电视":"已接电视")
                        +"\n卡带："+md.cartridgeTitle()+"\n1P："+(md.borrower(0)==null?"未借出":"已借出")+" · 2P："+(md.borrower(1)==null?"未借出":"已借出")+"；第二端口由开局选择允许。";
            }
            public boolean onPowerOn(net.minecraft.server.level.ServerPlayer p,HomeSystems.Connection c){return ((MdConsole)c.console()).powerOn(p,c);}
            public void onPowerOff(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){((MdConsole)c).powerOff();}
            public void onReset(net.minecraft.server.level.ServerPlayer p,HomeSystems.Connection c){((MdConsole)c.console()).reset(p);}
            public void onControllerDock(net.minecraft.server.level.ServerPlayer p,ExternalHomeConsoleBlockEntity c,int port){((MdConsole)c).dock(p,port);}
            public boolean isRunning(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){return ((MdConsole)c).running();}
            public void onLinked(net.minecraft.server.level.ServerPlayer p,HomeSystems.Connection c){p.displayClientMessage(net.minecraft.network.chat.Component.literal("MD2 已通过 AV 线连接电视。请打开电视，并点击 MD 机身米白电源滑块启动已写入的卡带。"),false);}
            public void onPlaybackStopped(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c,HomeSystems.StopReason why){((MdConsole)c).powerOff();}
            public void onInteract(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.InteractionHand h,HomeSystems.Connection c,net.minecraft.world.phys.BlockHitResult hit){((MdConsole)c.console()).interact(p,h);}
            public void onRemoved(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){((MdConsole)c).powerOff();((MdConsole)c).clearLoan();}
        });
        bus.addListener((BuildCreativeModeTabContentsEvent e)->{if(e.getTabKey().equals(ModCreativeTabs.FC.getKey())){e.accept(CONSOLE_ITEM);e.accept(CARTRIDGE);}});
    }
    static void hint(net.minecraft.server.level.ServerPlayer p){p.displayClientMessage(net.minecraft.network.chat.Component.literal("MD2：卡带右键老式电脑写游戏 → 视频线连接电视 → 插卡 → 打开电视和主机电源 → 选择进度与人数 → 点击对应手柄取用。默认公开 JNI 串流；旁观自动接入。"),false);}
}
