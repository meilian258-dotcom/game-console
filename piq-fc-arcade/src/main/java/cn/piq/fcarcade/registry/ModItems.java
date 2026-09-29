package cn.piq.fcarcade.registry;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.home.AvCableItem;
import cn.piq.fcarcade.home.FcCartridgeItem;
import cn.piq.fcarcade.home.FcCartridgeBoardItem;
import cn.piq.fcarcade.home.FcCartridgeShellItem;
import cn.piq.fcarcade.home.FcControllerItem;
import cn.piq.fcarcade.home.RetroTvBlockItem;
import cn.piq.fcarcade.home.SuborConsoleBlockItem;
import cn.piq.fcarcade.home.LcdTvBlockItem;
import cn.piq.fcarcade.world.DualCabinetBlockItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, FcArcadeMod.MOD_ID);

    public static final DeferredHolder<Item, cn.piq.fcarcade.cabinet.CabinetLinkCableItem> CABINET_LINK_CABLE =
            ITEMS.register("cabinet_link_cable", () -> new cn.piq.fcarcade.cabinet.CabinetLinkCableItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, BlockItem> FC_ARCADE =
            ITEMS.register("fc_arcade",
                    () -> new BlockItem(ModBlocks.FC_ARCADE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> STREAM_FC_ARCADE =
            ITEMS.register("stream_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.STREAM_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> DELUXE_FC_ARCADE =
            ITEMS.register("deluxe_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.DELUXE_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> DELUXE_STREAM_FC_ARCADE =
            ITEMS.register("deluxe_stream_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.DELUXE_STREAM_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> LEGACY_FC_ARCADE =
            ITEMS.register("legacy_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.LEGACY_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> WATERFRAMES_FC_ARCADE =
            ITEMS.register("waterframes_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.WATERFRAMES_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem>
            WATERFRAMES_TV_FC_ARCADE =
            ITEMS.register("waterframes_tv_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.WATERFRAMES_TV_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem>
            WATERFRAMES_TV_BOX_FC_ARCADE =
            ITEMS.register("waterframes_tv_box_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.WATERFRAMES_TV_BOX_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem>
            WATERFRAMES_PANEL_FC_ARCADE =
            ITEMS.register("waterframes_panel_fc_arcade",
                    () -> new BlockItem(
                            ModBlocks.WATERFRAMES_PANEL_FC_ARCADE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> LEADERBOARD_PANEL =
            ITEMS.register("leaderboard_panel",
                    () -> new BlockItem(
                            ModBlocks.LEADERBOARD_PANEL.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> FAMICOM_CONSOLE =
            ITEMS.register("famicom_console",
                    () -> new BlockItem(
                            ModBlocks.FAMICOM_CONSOLE.get(),
                            new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> SUBOR_CONSOLE =
            ITEMS.register("subor_console", () -> new SuborConsoleBlockItem(ModBlocks.SUBOR_CONSOLE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> RETRO_TV =
            ITEMS.register("retro_tv", () -> new RetroTvBlockItem(ModBlocks.RETRO_TV.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> PORTRAIT_CABINET =
            ITEMS.register("portrait_cabinet", () -> new BlockItem(ModBlocks.PORTRAIT_CABINET.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> DUAL_CABINET =
            ITEMS.register("dual_cabinet", () -> new DualCabinetBlockItem(ModBlocks.DUAL_CABINET.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> VINTAGE_TV =
            ITEMS.register("vintage_tv", () -> new LcdTvBlockItem(ModBlocks.VINTAGE_TV.get(),new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> LCD_TV =
            ITEMS.register("lcd_tv", () -> new LcdTvBlockItem(ModBlocks.LCD_TV.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> LARGE_LCD_TV =
            ITEMS.register("large_lcd_tv", () -> new cn.piq.fcarcade.home.LargeLcdTvBlockItem(ModBlocks.LARGE_LCD_TV.get(),new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> WIDE_LCD_TV =
            ITEMS.register("wide_lcd_tv", () -> new cn.piq.fcarcade.home.WideLcdTvBlockItem(ModBlocks.WIDE_LCD_TV.get(), new Item.Properties()));

    public static final DeferredHolder<Item, FcCartridgeItem> FC_CARTRIDGE =
            ITEMS.register("fc_cartridge", () -> new FcCartridgeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, FcCartridgeBoardItem> FC_CARTRIDGE_BOARD =
            ITEMS.register("fc_cartridge_board", () -> new FcCartridgeBoardItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, FcCartridgeShellItem> FC_CARTRIDGE_SHELL =
            ITEMS.register("fc_cartridge_shell", () -> new FcCartridgeShellItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, AvCableItem> AV_CABLE =
            ITEMS.register("av_cable", () -> new AvCableItem(new Item.Properties()));

    public static final DeferredHolder<Item, cn.piq.fcarcade.home.TvRemoteItem> TV_REMOTE =
            ITEMS.register("tv_remote", () -> new cn.piq.fcarcade.home.TvRemoteItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, cn.piq.fcarcade.home.DeviceDebugItem> DEBUG_SCREWDRIVER =
            ITEMS.register("debug_screwdriver", () -> new cn.piq.fcarcade.home.DeviceDebugItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, cn.piq.fcarcade.config.AdminTerminalItem> ADMIN_TERMINAL =
            ITEMS.register("admin_terminal", () -> new cn.piq.fcarcade.config.AdminTerminalItem(new Item.Properties().stacksTo(1)));

    // A controller is borrowed from a home console, not a free creative control token.
    public static final DeferredHolder<Item, FcControllerItem> FC_CONTROLLER =
            ITEMS.register("fc_controller", () -> new FcControllerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, cn.piq.fcarcade.home.HomeZapperItem> FC_ZAPPER =
            ITEMS.register("fc_zapper", () -> new cn.piq.fcarcade.home.HomeZapperItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, BlockItem> CARTRIDGE_COMPUTER =
            ITEMS.register("cartridge_computer", () -> new BlockItem(ModBlocks.CARTRIDGE_COMPUTER.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> ZAPPER_STAND =
            ITEMS.register("zapper_stand", () -> new BlockItem(ModBlocks.ZAPPER_STAND.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, cn.piq.fcarcade.home.ZapperStandCableItem> ZAPPER_STAND_CABLE =
            ITEMS.register("zapper_stand_cable", () -> new cn.piq.fcarcade.home.ZapperStandCableItem(new Item.Properties()));

    public static final DeferredHolder<Item, Item> ARCADE_COIN = ITEMS.register("arcade_coin", () -> new cn.piq.fcarcade.cabinet.ArcadeCoinItem(new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> GRAY_CRT_TV = ITEMS.register("gray_crt_tv", () -> new BlockItem(ModBlocks.GRAY_CRT_TV.get(),new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> RED_CRT_TV = ITEMS.register("red_crt_tv", () -> new BlockItem(ModBlocks.RED_CRT_TV.get(),new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> PANEL_TV_2 = ITEMS.register("panel_tv_2", () -> new cn.piq.fcarcade.home.PanelTvBlockItem(ModBlocks.PANEL_TV_2.get(),new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> PANEL_TV_3 = ITEMS.register("panel_tv_3", () -> new cn.piq.fcarcade.home.PanelTvBlockItem(ModBlocks.PANEL_TV_3.get(),new Item.Properties()));

    private ModItems() {
    }
}
