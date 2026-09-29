package cn.piq.fcarcade.core;

import cn.piq.fcarcade.core.wasm.NamcoWasmNesCore;
import cn.piq.fcarcade.core.libretro.GenericLibretroNesCore;
import cn.piq.fcarcade.core.wasm.WasmNesCore;
import cn.piq.fcarcade.core.wasm.ZapperWasmNesCore;
import cn.piq.fcarcade.session.NesCoreVariant;

/** Shared explicit selection for client workers and headless server workers. */
public final class NesCores {
    private NesCores() {}
    public static NesCore create(NesCoreVariant variant) {
        return switch(variant) {
            case LEGACY -> new WasmNesCore();
            case ZAPPER_V1 -> new ZapperWasmNesCore();
            case MAPPER19_V1 -> new NamcoWasmNesCore();
            case LIBRETRO_V1 -> new GenericLibretroNesCore(false);
            case LIBRETRO_ZAPPER_V1 -> new GenericLibretroNesCore(true);
        };
    }
    public static String moduleResource(NesCoreVariant variant) {
        return switch(variant) {
            case LEGACY -> "/core/nes_rust_wasm_bg.wasm";
            case ZAPPER_V1 -> ZapperWasmNesCore.MODULE_RESOURCE;
            case MAPPER19_V1 -> NamcoWasmNesCore.MODULE_RESOURCE;
            case LIBRETRO_V1, LIBRETRO_ZAPPER_V1 -> cn.piq.fcarcade.core.libretro.LibretroNesCore.MODULE_RESOURCE;
        };
    }
}
