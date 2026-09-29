package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

/** New forward-anchored 2x2 / 3x2 LCDs. Layout is fixed by block identity. */
public final class PanelTvBlock extends RetroTvBlock {
    public static final MapCodec<PanelTvBlock> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
            Codec.BOOL.fieldOf("wide").forGetter((PanelTvBlock block)->UserTvLayout.width(block.displayStyle())==3),
            Codec.BOOL.fieldOf("wall").forGetter((PanelTvBlock block)->UserTvLayout.wall(block.displayStyle())),
            propertiesCodec()).apply(instance,(wide,wall,properties)->new PanelTvBlock(properties,style(wide,wall))));
    @Override protected MapCodec<? extends RetroTvBlock> codec(){return CODEC;}
    private static ArcadeDisplayStyle style(boolean wide,boolean wall){
        return wide?(wall?ArcadeDisplayStyle.HOME_PANEL_3_WALL:ArcadeDisplayStyle.HOME_PANEL_3)
                :(wall?ArcadeDisplayStyle.HOME_PANEL_2_WALL:ArcadeDisplayStyle.HOME_PANEL_2);
    }
    public PanelTvBlock(Properties properties,ArcadeDisplayStyle style){super(properties,style);if(!UserTvLayout.panel(style))throw new IllegalArgumentException("Panel style");}
    @Override public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,net.minecraft.core.BlockPos pos,BlockState state){
        return new net.minecraft.world.item.ItemStack(PanelTvStructure.item(UserTvLayout.width(displayStyle())==3));
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){
        Direction face=context.getClickedFace();
        if(face==Direction.DOWN)return null;
        boolean wall=face.getAxis().isHorizontal();
        var block=PanelTvStructure.block(UserTvLayout.width(displayStyle())==3,wall);
        var state=block.defaultBlockState().setValue(FACING,wall?face:context.getHorizontalDirection().getOpposite());
        return PanelTvStructure.canPlace(context,state)?state:null;
    }
}
