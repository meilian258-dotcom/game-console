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
        ContentCards.register(SYSTEM,new ContentCards.Adapter(CARTRIDGE,"MD2",java.util.Set.of("md","bin","gen"),cn.piq.mdhome.client.MdRom::validate));
        HomeApplianceService.registerControls(SYSTEM,MdControls::pick);
        HomeSystems.register(SYSTEM,new HomeSystems.ServerHooks(){
            public boolean onPowerOn(net.minecraft.server.level.ServerPlayer p,HomeSystems.Connection c){return ((MdConsole)c.console()).powerOn(p,c);}
            public void onPowerOff(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){((MdConsole)c).powerOff();}
            public boolean isRunning(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){return ((MdConsole)c).running();}
            public void onLinked(net.minecraft.server.level.ServerPlayer p,HomeSystems.Connection c){p.displayClientMessage(net.minecraft.network.chat.Component.literal("MD2 已通过 AV 线连接电视。请打开电视，并点击 MD 机身米白电源滑块启动已写入的卡带。"),false);}
            public void onPlaybackStopped(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c,HomeSystems.StopReason why){((MdConsole)c).powerOff();}
            public void onInteract(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.InteractionHand h,HomeSystems.Connection c,net.minecraft.world.phys.BlockHitResult hit){((MdConsole)c.console()).interact(p,h);}
            public void onRemoved(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){((MdConsole)c).clearLoan();}
        });
        bus.addListener((BuildCreativeModeTabContentsEvent e)->{if(e.getTabKey().equals(ModCreativeTabs.FC.getKey())){e.accept(CONSOLE_ITEM);e.accept(CARTRIDGE);}});
    }
    static void hint(net.minecraft.server.level.ServerPlayer p){p.displayClientMessage(net.minecraft.network.chat.Component.literal("MD2：卡带右键老式电脑写游戏 → AV 线依次右键主机/电视 → 插卡 → 空手右键借 1P 手柄 → 点击机身米白电源滑块。默认 JNI；当前为单人本机画面。"),false);}
}
