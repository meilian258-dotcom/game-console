// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.cabinet;

import cn.piq.retro.api.RetroFrame;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Bounded independent video pictures and stereo PCM blocks. Call codecs off game/render threads. */
public final class CabinetMediaCodec {
    public static final int MAX_WIDTH=384,MAX_HEIGHT=288;
    public static final int MAX_RAW_BYTES=MAX_WIDTH*MAX_HEIGHT*2;
    public static final int MAX_COMPRESSED_BYTES=131072;
    public static final int MAX_PCM_BYTES=19200;
    private CabinetMediaCodec(){}

    /** Pixels are unrotated; presentation metadata is preserved exactly through scaling. */
    public record Encoded(int width,int height,float displayAspect,int rotation,byte[] data){
        public Encoded{
            if(width<1||height<1||width>MAX_WIDTH||height>MAX_HEIGHT
                    ||!Float.isFinite(displayAspect)||displayAspect<.1F||displayAspect>10F
                    ||rotation<0||rotation>3||data==null||data.length<1||data.length>MAX_COMPRESSED_BYTES)
                throw new IllegalArgumentException("Invalid cabinet media envelope");
            data=data.clone();
        }
        @Override public byte[] data(){return data.clone();}
    }

    /** At most three 3/4-size retries; even high-entropy pictures keep a decodable video fallback. */
    public static Encoded encodeVideo(RetroFrame frame){
        Objects.requireNonNull(frame,"frame");
        double scale=Math.min(1.0,Math.min((double)MAX_WIDTH/frame.width(),(double)MAX_HEIGHT/frame.height()));
        int width=Math.max(1,(int)Math.floor(frame.width()*scale));
        int height=Math.max(1,(int)Math.floor(frame.height()*scale));
        int[] pixels=frame.abgr();
        for(int attempt=0;attempt<4;attempt++){
            // Always sample the original immutable frame, never compound quantization/scaling errors.
            byte[] raw=new byte[width*height*2];
            for(int y=0;y<height;y++){
                int sourceY=(int)((long)y*frame.height()/height);
                for(int x=0;x<width;x++){
                    int sourceX=(int)((long)x*frame.width()/width);
                    int abgr=pixels[sourceY*frame.width()+sourceX];
                    int rgb565=((abgr&255)>>>3)<<11|(((abgr>>>8)&255)>>>2)<<5|((abgr>>>16)&255)>>>3;
                    int p=(y*width+x)*2;raw[p]=(byte)rgb565;raw[p+1]=(byte)(rgb565>>>8);
                }
            }
            Deflater deflater=new Deflater(Deflater.BEST_SPEED);
            try{
                deflater.setInput(raw);deflater.finish();
                ByteArrayOutputStream compressed=new ByteArrayOutputStream(Math.min(raw.length,8192));
                byte[] chunk=new byte[8192];boolean exceeded=false;
                while(!deflater.finished()){
                    int count=deflater.deflate(chunk);
                    if(count==0)throw new IllegalStateException("Video deflater made no progress");
                    if(compressed.size()>MAX_COMPRESSED_BYTES-count){exceeded=true;break;}
                    compressed.write(chunk,0,count);
                }
                if(!exceeded)return new Encoded(width,height,frame.displayAspect(),frame.rotation(),compressed.toByteArray());
            }finally{deflater.end();}
            width=Math.max(1,width*3/4);height=Math.max(1,height*3/4);
        }
        // Final attempt is <=162x121: zlib's worst-case overhead easily fits 128 KiB.
        throw new IllegalStateException("Bounded video fallback exceeded the zlib byte budget");
    }

    /** Strict zlib envelope: exact decoded size, finished stream, no dictionary or trailing bytes. */
    public static int[] decodeVideo(Encoded frame){
        Objects.requireNonNull(frame,"frame");
        int expected=Math.multiplyExact(Math.multiplyExact(frame.width(),frame.height()),2);
        if(expected>MAX_RAW_BYTES)throw new IllegalArgumentException("Video decoded byte budget exceeded");
        byte[] raw=new byte[expected];Inflater inflater=new Inflater();
        try{
            // Enclosing class can read the privately owned record array without making another copy.
            inflater.setInput(frame.data);int offset=0;
            while(offset<expected){
                int count=inflater.inflate(raw,offset,expected-offset);
                offset+=count;
                if(count==0){
                    if(inflater.finished()||inflater.needsInput()||inflater.needsDictionary())break;
                    throw new IllegalArgumentException("Video inflater made no progress");
                }
            }
            if(offset!=expected)throw new IllegalArgumentException("Truncated cabinet video");
            // A single extra output byte is sufficient to reject arbitrary decompression expansion.
            byte[] excess=new byte[1];int extra=inflater.finished()?0:inflater.inflate(excess);
            if(extra!=0||!inflater.finished()||inflater.needsDictionary()||inflater.getRemaining()!=0)
                throw new IllegalArgumentException("Cabinet video size/trailing-data mismatch");
        }catch(DataFormatException invalid){throw new IllegalArgumentException("Invalid compressed cabinet video",invalid);}
        finally{inflater.end();}
        int[] abgr=new int[frame.width()*frame.height()];
        for(int i=0;i<abgr.length;i++){
            int packed=(raw[i*2]&255)|((raw[i*2+1]&255)<<8);
            int r=(packed>>>11)&31,g=(packed>>>5)&63,b=packed&31;
            r=(r<<3)|(r>>>2);g=(g<<2)|(g>>>4);b=(b<<3)|(b>>>2);
            abgr[i]=0xff000000|r|(g<<8)|(b<<16);
        }
        return abgr;
    }

    /** A block is at most 100 ms of PCM48k; offset and length count shorts, not stereo frames. */
    public static byte[] encodePcm(short[] pcm,int offset,int length){
        Objects.requireNonNull(pcm,"pcm");
        if(offset<0||length<0||(offset&1)!=0||(length&1)!=0||length>MAX_PCM_BYTES/2
                ||offset>pcm.length-length)throw new IllegalArgumentException("Invalid stereo PCM block");
        byte[] bytes=new byte[length*2];
        for(int i=0;i<length;i++){short value=pcm[offset+i];bytes[i*2]=(byte)value;bytes[i*2+1]=(byte)(value>>>8);}
        return bytes;
    }

    public static short[] decodePcm(byte[] bytes){
        Objects.requireNonNull(bytes,"bytes");
        if(bytes.length>MAX_PCM_BYTES||(bytes.length&3)!=0)throw new IllegalArgumentException("Invalid stereo PCM bytes");
        short[] pcm=new short[bytes.length/2];
        for(int i=0;i<pcm.length;i++)pcm[i]=(short)((bytes[i*2]&255)|((bytes[i*2+1]&255)<<8));
        return pcm;
    }
}
