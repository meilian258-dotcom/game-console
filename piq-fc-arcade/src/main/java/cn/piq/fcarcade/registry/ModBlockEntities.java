package cn.piq.fcarcade.registry;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.home.SuborPartBlockEntity;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.HomeTvPartBlockEntity;
import cn.piq.fcarcade.home.WideLcdTvPartBlockEntity;
import cn.piq.fcarcade.home.LargeLcdTvPartBlockEntity;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import cn.piq.fcarcade.world.LeaderboardPanelBlockEntity;
import cn.piq.fcarcade.world.DualCabinetBlockEntity;
import cn.piq.fcarcade.world.DualCabinetPartBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(
                    Registries.BLOCK_ENTITY_TYPE,
                    FcArcadeMod.MOD_ID);

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<LegacyFcArcadeBlockEntity>> LEGACY_FC_ARCADE =
            BLOCK_ENTITIES.register(
                    "legacy_fc_arcade",
                    () -> new BlockEntityType<>(
                            LegacyFcArcadeBlockEntity::new,
                            Set.of(ModBlocks.LEGACY_FC_ARCADE.get()),
                            null));

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<LeaderboardPanelBlockEntity>> LEADERBOARD_PANEL =
            BLOCK_ENTITIES.register(
                    "leaderboard_panel",
                    () -> new BlockEntityType<>(
                            LeaderboardPanelBlockEntity::new,
                            Set.of(ModBlocks.LEADERBOARD_PANEL.get()),
                            null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HomeConsoleBlockEntity>> HOME_CONSOLE =
            BLOCK_ENTITIES.register("home_console", () -> new BlockEntityType<>(
                    HomeConsoleBlockEntity::new, Set.of(ModBlocks.FAMICOM_CONSOLE.get(), ModBlocks.SUBOR_CONSOLE.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SuborPartBlockEntity>> SUBOR_PART =
            BLOCK_ENTITIES.register("subor_part", () -> new BlockEntityType<>(
                    SuborPartBlockEntity::new, Set.of(ModBlocks.SUBOR_PART.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HomeTvBlockEntity>> HOME_TV =
            BLOCK_ENTITIES.register("home_tv", () -> new BlockEntityType<>(
                    HomeTvBlockEntity::new, Set.of(ModBlocks.RETRO_TV.get(), ModBlocks.LCD_TV.get(), ModBlocks.WIDE_LCD_TV.get(), ModBlocks.LARGE_LCD_TV.get(), ModBlocks.VINTAGE_TV.get(), ModBlocks.GRAY_CRT_TV.get(), ModBlocks.RED_CRT_TV.get(), ModBlocks.PANEL_TV_2.get(), ModBlocks.PANEL_TV_2_WALL.get(), ModBlocks.PANEL_TV_3.get(), ModBlocks.PANEL_TV_3_WALL.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cn.piq.fcarcade.home.PanelTvPartBlockEntity>> PANEL_TV_PART =
            BLOCK_ENTITIES.register("panel_tv_part", () -> new BlockEntityType<>(cn.piq.fcarcade.home.PanelTvPartBlockEntity::new,Set.of(ModBlocks.PANEL_TV_PART.get()),null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LargeLcdTvPartBlockEntity>> LARGE_LCD_TV_PART =
            BLOCK_ENTITIES.register("large_lcd_tv_part", () -> new BlockEntityType<>(
                    LargeLcdTvPartBlockEntity::new, Set.of(ModBlocks.LARGE_LCD_TV_PART.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WideLcdTvPartBlockEntity>> WIDE_LCD_TV_PART =
            BLOCK_ENTITIES.register("wide_lcd_tv_part", () -> new BlockEntityType<>(
                    WideLcdTvPartBlockEntity::new, Set.of(ModBlocks.WIDE_LCD_TV_PART.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HomeTvPartBlockEntity>> HOME_TV_PART =
            BLOCK_ENTITIES.register("home_tv_part", () -> new BlockEntityType<>(
                    HomeTvPartBlockEntity::new, Set.of(ModBlocks.RETRO_TV_PART.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cn.piq.fcarcade.world.PortraitCabinetBlockEntity>> PORTRAIT_CABINET =
            BLOCK_ENTITIES.register("portrait_cabinet", () -> new BlockEntityType<>(
                    cn.piq.fcarcade.world.PortraitCabinetBlockEntity::new, Set.of(ModBlocks.PORTRAIT_CABINET.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DualCabinetBlockEntity>> DUAL_CABINET =
            BLOCK_ENTITIES.register("dual_cabinet", () -> new BlockEntityType<>(
                    DualCabinetBlockEntity::new, Set.of(ModBlocks.DUAL_CABINET.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DualCabinetPartBlockEntity>> DUAL_CABINET_PART =
            BLOCK_ENTITIES.register("dual_cabinet_part", () -> new BlockEntityType<>(
                    DualCabinetPartBlockEntity::new, Set.of(ModBlocks.DUAL_CABINET_PART.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cn.piq.fcarcade.home.CartridgeComputerBlockEntity>> CARTRIDGE_COMPUTER =
            BLOCK_ENTITIES.register("cartridge_computer", () -> new BlockEntityType<>(
                    cn.piq.fcarcade.home.CartridgeComputerBlockEntity::new, Set.of(ModBlocks.CARTRIDGE_COMPUTER.get()), null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cn.piq.fcarcade.home.ZapperStandBlockEntity>> ZAPPER_STAND =
            BLOCK_ENTITIES.register("zapper_stand", () -> new BlockEntityType<>(
                    cn.piq.fcarcade.home.ZapperStandBlockEntity::new, Set.of(ModBlocks.ZAPPER_STAND.get()), null));

    private ModBlockEntities() {
    }
}
