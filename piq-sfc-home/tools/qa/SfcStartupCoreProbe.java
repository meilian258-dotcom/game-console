package cn.piq.sfcarcade.core;

import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import java.security.MessageDigest;
import java.util.*;

/** Real frozen SFC6 WASM timings with the existing original 32 KiB 65816 green-screen fixture. */
public final class SfcStartupCoreProbe {
    private static long elapsed(long began){return System.nanoTime()-began;}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[]args)throws Exception{
        List<Map<String,Object>> runs=new ArrayList<>();String previous=null;
        for(int index=0;index<2;index++){
            Map<String,Object> out=new LinkedHashMap<>();long total=System.nanoTime(),at=total;
            WasmSfcCore core=new WasmSfcCore();out.put("construct_ns",elapsed(at));
            try{
                at=System.nanoTime();byte[] rom=SfcLegalTestRom.create();SfcRomImage image=SfcRomImage.fromBytes(rom);out.put("original_fixture_and_parse_ns",elapsed(at));
                at=System.nanoTime();core.loadRom(image);out.put("load_rom_ns",elapsed(at));
                at=System.nanoTime();var probed=core.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);out.put("probe_frame_ns",elapsed(at));
                double fps=probed.videoMode().targetFramesPerSecond();require(fps>59&&fps<61,"Original fixture must be NTSC");
                at=System.nanoTime();core.reset(true);out.put("reset_ns",elapsed(at));
                at=System.nanoTime();byte[] state=core.saveState();out.put("save_state_ns",elapsed(at));
                at=System.nanoTime();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state));out.put("state_hash_ns",elapsed(at));
                require(state.length>0,"Initial state is not empty");if(previous!=null)require(previous.equals(hash),"Independent initial states must agree");previous=hash;
                at=System.nanoTime();var frame=core.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);byte[] pixels=new byte[frame.videoMode().requiredRgbaBytes()];core.copyRgbaFrame(pixels);out.put("first_game_frame_and_pixels_ns",elapsed(at));
                require(pixels.length>0,"First real frame pixels exist");out.put("width",frame.videoMode().width());out.put("height",frame.videoMode().height());out.put("fps",fps);out.put("state_bytes",state.length);out.put("initial_state_sha256",hash);
            }finally{at=System.nanoTime();core.close();out.put("close_ns",elapsed(at));}
            out.put("total_ns",elapsed(total));out.put("ordinal",index+1);runs.add(out);
        }
        StringBuilder json=new StringBuilder("{\"passed\":true,\"commercial_roms\":false,\"minecraft_gameplay\":false,\"samples\":[");
        for(int i=0;i<runs.size();i++){if(i>0)json.append(',');json.append('{');boolean first=true;for(var e:runs.get(i).entrySet()){if(!first)json.append(',');first=false;json.append('"').append(e.getKey()).append("\":");if(e.getValue() instanceof String)json.append('"').append(e.getValue()).append('"');else json.append(e.getValue());}json.append('}');}
        System.out.println(json.append("]}"));
    }
}
