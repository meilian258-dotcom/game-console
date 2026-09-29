package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import com.mojang.serialization.MapCodec;

/** New six-cell variant; old single-cell LCD blocks are never upgraded in place. */
public final class LargeLcdTvBlock extends RetroTvBlock {
    public static final MapCodec<LargeLcdTvBlock> CODEC=simpleCodec(LargeLcdTvBlock::new);
    public LargeLcdTvBlock(Properties properties) { super(properties,ArcadeDisplayStyle.HOME_LARGE_LCD_TV); }
    @Override protected MapCodec<? extends RetroTvBlock> codec() { return CODEC; }
}
