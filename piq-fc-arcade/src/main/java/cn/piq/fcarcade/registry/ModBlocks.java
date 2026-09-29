package cn.piq.fcarcade.registry;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.home.RetroTvBlock;
import cn.piq.fcarcade.home.SuborConsoleBlock;
import cn.piq.fcarcade.home.SuborPartBlock;
import cn.piq.fcarcade.home.LcdTvBlock;
import cn.piq.fcarcade.home.WideLcdTvBlock;
import cn.piq.fcarcade.home.WideLcdTvPartBlock;
import cn.piq.fcarcade.home.LargeLcdTvBlock;
import cn.piq.fcarcade.home.LargeLcdTvPartBlock;
import cn.piq.fcarcade.home.HomeTvPartBlock;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.fcarcade.world.DualCabinetBlock;
import cn.piq.fcarcade.world.DualCabinetPartBlock;
import cn.piq.fcarcade.world.FamicomConsoleBlock;
import cn.piq.fcarcade.world.DeluxeFcArcadeBlock;
import cn.piq.fcarcade.world.DeluxeStreamFcArcadeBlock;
import cn.piq.fcarcade.world.LegacyFcArcadeBlock;
import cn.piq.fcarcade.world.LeaderboardPanelBlock;
import cn.piq.fcarcade.world.StreamFcArcadeBlock;
import cn.piq.fcarcade.world.WaterFramesFcArcadeBlock;
import cn.piq.fcarcade.world.WaterFramesPanelFcArcadeBlock;
import cn.piq.fcarcade.world.WaterFramesTvBoxFcArcadeBlock;
import cn.piq.fcarcade.world.WaterFramesTvFcArcadeBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, FcArcadeMod.MOD_ID);

    public static final DeferredHolder<Block, FcArcadeBlock> FC_ARCADE =
            BLOCKS.register("fc_arcade", () -> new FcArcadeBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    public static final DeferredHolder<Block, StreamFcArcadeBlock> STREAM_FC_ARCADE =
            BLOCKS.register("stream_fc_arcade", () -> new StreamFcArcadeBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    public static final DeferredHolder<Block, DeluxeFcArcadeBlock> DELUXE_FC_ARCADE =
            BLOCKS.register("deluxe_fc_arcade", () -> new DeluxeFcArcadeBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    public static final DeferredHolder<Block, DeluxeStreamFcArcadeBlock>
            DELUXE_STREAM_FC_ARCADE =
            BLOCKS.register("deluxe_stream_fc_arcade",
                    () -> new DeluxeStreamFcArcadeBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F, 6.0F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, LegacyFcArcadeBlock>
            LEGACY_FC_ARCADE =
            BLOCKS.register("legacy_fc_arcade",
                    () -> new LegacyFcArcadeBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F, 6.0F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, WaterFramesFcArcadeBlock>
            WATERFRAMES_FC_ARCADE =
            BLOCKS.register("waterframes_fc_arcade",
                    () -> new WaterFramesFcArcadeBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F, 6.0F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, WaterFramesTvFcArcadeBlock>
            WATERFRAMES_TV_FC_ARCADE =
            BLOCKS.register("waterframes_tv_fc_arcade",
                    () -> new WaterFramesTvFcArcadeBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F, 6.0F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, WaterFramesTvBoxFcArcadeBlock>
            WATERFRAMES_TV_BOX_FC_ARCADE =
            BLOCKS.register("waterframes_tv_box_fc_arcade",
                    () -> new WaterFramesTvBoxFcArcadeBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F, 6.0F)
                                    .sound(SoundType.WOOD)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, WaterFramesPanelFcArcadeBlock>
            WATERFRAMES_PANEL_FC_ARCADE =
            BLOCKS.register("waterframes_panel_fc_arcade",
                    () -> new WaterFramesPanelFcArcadeBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(1.0F, 2.0F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, LeaderboardPanelBlock>
            LEADERBOARD_PANEL =
            BLOCKS.register("leaderboard_panel",
                    () -> new LeaderboardPanelBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(1.5F, 3.0F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, FamicomConsoleBlock>
            FAMICOM_CONSOLE =
            BLOCKS.register("famicom_console",
                    () -> new FamicomConsoleBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(1.5F, 3.0F)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()));

    public static final DeferredHolder<Block, SuborConsoleBlock> SUBOR_CONSOLE =
            BLOCKS.register("subor_console", () -> new SuborConsoleBlock(
                    BlockBehaviour.Properties.of().strength(1.5F, 3.0F)
                            .sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, SuborPartBlock> SUBOR_PART =
            BLOCKS.register("subor_part", () -> new SuborPartBlock(
                    BlockBehaviour.Properties.of().strength(1.5F, 3.0F)
                            .sound(SoundType.METAL).noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, RetroTvBlock> RETRO_TV =
            BLOCKS.register("retro_tv", () -> new RetroTvBlock(
                    BlockBehaviour.Properties.of().strength(1.5F, 3.0F)
                            .sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, cn.piq.fcarcade.home.VintageTvBlock> VINTAGE_TV =
            BLOCKS.register("vintage_tv", () -> new cn.piq.fcarcade.home.VintageTvBlock(
                    BlockBehaviour.Properties.of().strength(1.5F,3.0F).sound(SoundType.WOOD)
                            .noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, LcdTvBlock> LCD_TV =
            BLOCKS.register("lcd_tv", () -> new LcdTvBlock(
                    BlockBehaviour.Properties.of().strength(1.5F, 3.0F)
                            .sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, WideLcdTvBlock> WIDE_LCD_TV =
            BLOCKS.register("wide_lcd_tv", () -> new WideLcdTvBlock(
                    BlockBehaviour.Properties.of().strength(1.5F,3.0F).sound(SoundType.METAL)
                            .noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)));
    public static final DeferredHolder<Block, WideLcdTvPartBlock> WIDE_LCD_TV_PART =
            BLOCKS.register("wide_lcd_tv_part", () -> new WideLcdTvPartBlock(
                    BlockBehaviour.Properties.of().strength(1.5F,3.0F).sound(SoundType.METAL)
                            .noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, LargeLcdTvBlock> LARGE_LCD_TV =
            BLOCKS.register("large_lcd_tv", () -> new LargeLcdTvBlock(
                    BlockBehaviour.Properties.of().strength(1.5F,3.0F).sound(SoundType.METAL)
                            .noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)));
    public static final DeferredHolder<Block, LargeLcdTvPartBlock> LARGE_LCD_TV_PART =
            BLOCKS.register("large_lcd_tv_part", () -> new LargeLcdTvPartBlock(
                    BlockBehaviour.Properties.of().strength(1.5F,3.0F).sound(SoundType.METAL)
                            .noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, HomeTvPartBlock> RETRO_TV_PART =
            BLOCKS.register("retro_tv_part", () -> new HomeTvPartBlock(
                    BlockBehaviour.Properties.of().strength(1.5F, 3.0F)
                            .sound(SoundType.WOOD).noOcclusion().noLootTable()
                            .pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, cn.piq.fcarcade.world.PortraitCabinetBlock> PORTRAIT_CABINET =
            BLOCKS.register("portrait_cabinet", () -> new cn.piq.fcarcade.world.PortraitCabinetBlock(
                    BlockBehaviour.Properties.of().strength(3.0F,6.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, DualCabinetBlock> DUAL_CABINET =
            BLOCKS.register("dual_cabinet", () -> new DualCabinetBlock(
                    BlockBehaviour.Properties.of().strength(3.0F, 6.0F)
                            .sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, DualCabinetPartBlock> DUAL_CABINET_PART =
            BLOCKS.register("dual_cabinet_part", () -> new DualCabinetPartBlock(
                    BlockBehaviour.Properties.of().strength(3.0F, 6.0F)
                            .sound(SoundType.METAL).noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, cn.piq.fcarcade.home.CartridgeComputerBlock> CARTRIDGE_COMPUTER =
            BLOCKS.register("cartridge_computer", () -> new cn.piq.fcarcade.home.CartridgeComputerBlock(
                    BlockBehaviour.Properties.of().strength(1.5F, 3.0F).sound(SoundType.METAL)
                            .noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, cn.piq.fcarcade.home.ZapperStandBlock> ZAPPER_STAND =
            BLOCKS.register("zapper_stand", () -> new cn.piq.fcarcade.home.ZapperStandBlock(
                    BlockBehaviour.Properties.of().strength(1.5F,3.0F).sound(SoundType.METAL)
                            .noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Block, cn.piq.fcarcade.home.UserTvBlock> GRAY_CRT_TV =
            BLOCKS.register("gray_crt_tv", () -> new cn.piq.fcarcade.home.UserTvBlock(tvProperties(),cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_GRAY_CRT));
    public static final DeferredHolder<Block, cn.piq.fcarcade.home.UserTvBlock> RED_CRT_TV =
            BLOCKS.register("red_crt_tv", () -> new cn.piq.fcarcade.home.UserTvBlock(tvProperties(),cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RED_CRT));
    public static final DeferredHolder<Block, cn.piq.fcarcade.home.PanelTvBlock> PANEL_TV_2 =
            BLOCKS.register("panel_tv_2", () -> new cn.piq.fcarcade.home.PanelTvBlock(tvProperties().noLootTable(),cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_PANEL_2));
    public static final DeferredHolder<Block, cn.piq.fcarcade.home.PanelTvBlock> PANEL_TV_2_WALL =
            BLOCKS.register("panel_tv_2_wall", () -> new cn.piq.fcarcade.home.PanelTvBlock(tvProperties().noLootTable(),cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_PANEL_2_WALL));
    public static final DeferredHolder<Block, cn.piq.fcarcade.home.PanelTvBlock> PANEL_TV_3 =
            BLOCKS.register("panel_tv_3", () -> new cn.piq.fcarcade.home.PanelTvBlock(tvProperties().noLootTable(),cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_PANEL_3));
    public static final DeferredHolder<Block, cn.piq.fcarcade.home.PanelTvBlock> PANEL_TV_3_WALL =
            BLOCKS.register("panel_tv_3_wall", () -> new cn.piq.fcarcade.home.PanelTvBlock(tvProperties().noLootTable(),cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_PANEL_3_WALL));
    public static final DeferredHolder<Block, cn.piq.fcarcade.home.PanelTvPartBlock> PANEL_TV_PART =
            BLOCKS.register("panel_tv_part", () -> new cn.piq.fcarcade.home.PanelTvPartBlock(tvProperties().noLootTable()));

    private static BlockBehaviour.Properties tvProperties(){return BlockBehaviour.Properties.of().strength(1.5F,3.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK);}

    private ModBlocks() {
    }
}
