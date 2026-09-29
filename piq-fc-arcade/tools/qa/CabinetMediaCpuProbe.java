import cn.piq.fcarcade.cabinet.CabinetMediaCodec;
import cn.piq.retro.api.RetroFrame;
import java.util.*;

/** Synthetic generated pixels, not a commercial game or a network/FPS benchmark. */
public final class CabinetMediaCpuProbe{
    static int[] pixels(int w,int h,int kind){
        int[] out=new int[w*h];Random random=new Random(616161);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[y*w+x]=switch(kind){
            case 0 -> 0xff000000;
            case 1 -> (((x/8)+(y/8))&1)==0?0xff0000ff:0xff00ff00;
            case 2 -> 0xff000000|((x*255/w))|((y*255/h)<<8)|(((x+y)*255/(w+h))<<16);
            default -> random.nextInt()|0xff000000;
        };return out;
    }
    public static void main(String[] args){
        String[] names={"solid_320x224","checker_320x224","gradient_384x288","entropy_384x288","scaled_checker_1024x768"};
        int[][] cases={{320,224,0},{320,224,1},{384,288,2},{384,288,3},{1024,768,1}};
        StringBuilder result=new StringBuilder("{\"passed\":true,\"synthetic_pixels\":true,\"commercial_roms\":false,\"network_test\":false,\"iterations_per_case\":60,\"cases\":[");
        for(int c=0;c<cases.length;c++){
            int[] row=cases[c];var frame=new RetroFrame(row[0],row[1],pixels(row[0],row[1],row[2]),4F/3,0,new short[0]);
            for(int i=0;i<15;i++){var encoded=CabinetMediaCodec.encodeVideo(frame);if(encoded!=null)CabinetMediaCodec.decodeVideo(encoded);}
            long[] encode=new long[60],decode=new long[60];int encodedBytes=0,width=0,height=0,dropped=0;
            for(int i=0;i<60;i++){
                long start=System.nanoTime();var encoded=CabinetMediaCodec.encodeVideo(frame);encode[i]=System.nanoTime()-start;
                if(encoded==null){dropped++;continue;}
                encodedBytes=encoded.data().length;width=encoded.width();height=encoded.height();
                start=System.nanoTime();var restored=CabinetMediaCodec.decodeVideo(encoded);decode[i]=System.nanoTime()-start;
                if(restored.length!=width*height)throw new AssertionError("decoded size");
            }
            if(dropped!=0||encodedBytes>131072)throw new AssertionError("bounded picture fallback failed");
            if(c==3&&(width>=384||height>=288))throw new AssertionError("entropy did not use bounded downsample fallback");
            Arrays.sort(encode);Arrays.sort(decode);if(c>0)result.append(',');
            result.append(String.format(Locale.ROOT,"{\"name\":\"%s\",\"width\":%d,\"height\":%d,\"compressed_bytes\":%d,\"rgb565_raw_bytes\":%d,\"encode_median_ms\":%.4f,\"encode_p95_ms\":%.4f,\"decode_median_ms\":%.4f,\"decode_p95_ms\":%.4f,\"dropped\":%d,\"video_payload_bytes_per_second_at_20fps\":%d,\"video_plus_stereo_pcm_bytes_per_second_at_20fps\":%d,\"estimated_fps_with_1MiB_budget_and_pcm\":%.3f}",
                    names[c],width,height,encodedBytes,width*height*2,encode[30]/1e6,encode[56]/1e6,decode[30]/1e6,decode[56]/1e6,dropped,encodedBytes*20,encodedBytes*20+192000,Math.min(20.0,(1048576.0-192000)/encodedBytes)));
        }
        System.out.println(result.append("]}"));
    }
}
