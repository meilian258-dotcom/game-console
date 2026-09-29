package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import com.mojang.serialization.MapCodec;

/** New two-cell variant; old single-cell LCD blocks are never upgraded in place. */
public final class WideLcdTvBlock extends RetroTvBlock {
    public static final MapCodec<WideLcdTvBlock> CODEC=simpleCodec(WideLcdTvBlock::new);
    public WideLcdTvBlock(Properties properties) { super(properties,ArcadeDisplayStyle.HOME_WIDE_LCD_TV); }
    @Override protected MapCodec<? extends RetroTvBlock> codec() { return CODEC; }
}
