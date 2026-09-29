package cn.piq.fcarcade.cabinet;

import cn.piq.retro.api.RetroFrame;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetMediaCodecTest {
    static RetroFrame frame(int w,int h,int[] abgr){return new RetroFrame(w,h,abgr,4F/3,0,new short[0]);}
    static byte[] zipped(byte[] raw){
        Deflater d=new Deflater(1);try{d.setInput(raw);d.finish();var out=new ByteArrayOutputStream();byte[] b=new byte[8192];
            while(!d.finished()){int n=d.deflate(b);out.write(b,0,n);}return out.toByteArray();}finally{d.end();}
    }
    static CabinetMediaCodec.Encoded envelope(int w,int h,byte[] z){return new CabinetMediaCodec.Encoded(w,h,4F/3,0,z);}
    @Test void exact565PrimariesAndChannelOrder(){
        int[] p={0xff0000ff,0xff00ff00,0xffff0000,0xffffffff,0xff000000};
        var encoded=CabinetMediaCodec.encodeVideo(frame(5,1,p));assertNotNull(encoded);
        assertArrayEquals(p,CabinetMediaCodec.decodeVideo(encoded));
    }
    @Test void quantizationErrorIsBoundedAndAlphaIsOpaque(){
        Random random=new Random(42);int[] pixels=random.ints(320*32).toArray();
        int[] result=CabinetMediaCodec.decodeVideo(CabinetMediaCodec.encodeVideo(frame(320,32,pixels)));
        for(int i=0;i<pixels.length;i++){
            assertEquals(255,result[i]>>>24);
            assertTrue(Math.abs((pixels[i]&255)-(result[i]&255))<=7);
            assertTrue(Math.abs(((pixels[i]>>>8)&255)-((result[i]>>>8)&255))<=3);
            assertTrue(Math.abs(((pixels[i]>>>16)&255)-((result[i]>>>16)&255))<=7);
        }
    }
    @Test void downsamplingStaysBoundedPreservesPresentationAndNeverUpscales(){
        for(int[] dim:new int[][]{{2048,2048},{2048,1},{1,2048},{768,576},{320,224},{1,1}}){
            for(int rotation=0;rotation<4;rotation++){
                var source=new RetroFrame(dim[0],dim[1],new int[dim[0]*dim[1]],1.7777778F,rotation,new short[0]);
                var result=CabinetMediaCodec.encodeVideo(source);assertNotNull(result);
                assertTrue(result.width()<=384&&result.height()<=288);
                assertTrue(result.width()<=dim[0]&&result.height()<=dim[1]);
                assertEquals(source.displayAspect(),result.displayAspect());assertEquals(rotation,result.rotation());
                assertEquals(result.width()*result.height(),CabinetMediaCodec.decodeVideo(result).length);
            }
        }
    }
    @Test void nearestNeighbourUsesCorrectUnrotatedSourceCoordinates(){
        int[] pixels=new int[768*576];for(int y=0;y<576;y++)for(int x=0;x<768;x++)pixels[y*768+x]=((x/2+y/2)&1)==0?0xff0000ff:0xffff0000;
        var e=CabinetMediaCodec.encodeVideo(frame(768,576,pixels));assertEquals(384,e.width());assertEquals(288,e.height());
        var out=CabinetMediaCodec.decodeVideo(e);for(int y=0;y<288;y++)for(int x=0;x<384;x++)assertEquals(pixels[(y*2)*768+x*2],out[y*384+x]);
    }
    @Test void incompressibleVideoFallsBackWithoutTouchingAudio(){
        int[] pixels=new Random(217).ints(384*288).toArray();short[] pcm={1,-2,3,-4};
        var source=new RetroFrame(384,288,pixels,4F/3,0,pcm);
        var result=CabinetMediaCodec.encodeVideo(source);assertNotNull(result);
        assertEquals(288,result.width());assertEquals(216,result.height());
        assertTrue(result.data().length<=CabinetMediaCodec.MAX_COMPRESSED_BYTES);
        assertEquals(result.width()*result.height(),CabinetMediaCodec.decodeVideo(result).length);
        assertArrayEquals(new short[]{1,-2,3,-4},pcm);
        assertArrayEquals(pcm,CabinetMediaCodec.decodePcm(CabinetMediaCodec.encodePcm(pcm,0,4)));
    }
    @Test void normalPicturesRemainByteForByteIdenticalToOriginalEncoding(){
        for(int[] dimensions:new int[][]{{5,1},{320,224},{384,288},{768,576}}){
            int w=dimensions[0],h=dimensions[1];int[] pixels=new int[w*h];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels[y*w+x]=((x/8+y/8)&1)==0?0xff34ab12:0xffef4576;
            double scale=Math.min(1.0,Math.min(384.0/w,288.0/h));int ew=Math.max(1,(int)Math.floor(w*scale)),eh=Math.max(1,(int)Math.floor(h*scale));
            byte[] originalRaw=new byte[ew*eh*2];
            for(int y=0;y<eh;y++)for(int x=0;x<ew;x++){
                int source=pixels[(int)((long)y*h/eh)*w+(int)((long)x*w/ew)];
                int packed=((source&255)>>>3)<<11|(((source>>>8)&255)>>>2)<<5|((source>>>16)&255)>>>3;
                int index=(y*ew+x)*2;originalRaw[index]=(byte)packed;originalRaw[index+1]=(byte)(packed>>>8);
            }
            var result=CabinetMediaCodec.encodeVideo(frame(w,h,pixels));assertNotNull(result);
            assertEquals(ew,result.width());assertEquals(eh,result.height());assertArrayEquals(zipped(originalRaw),result.data());
        }
    }
    @Test void fallbackPreservesUnrotatedAspectAndAllRotations(){
        int[] noise=new Random(900).ints(384*288).toArray();
        for(int rotation=0;rotation<4;rotation++)for(float aspect:new float[]{1F,4F/3F,16F/9F}){
            var result=CabinetMediaCodec.encodeVideo(new RetroFrame(384,288,noise,aspect,rotation,new short[0]));
            assertNotNull(result);assertTrue(result.width()<384);assertEquals(aspect,result.displayAspect());assertEquals(rotation,result.rotation());
            assertEquals(result.width()*result.height(),CabinetMediaCodec.decodeVideo(result).length);
        }
    }
    @Test void fallbackNearestNeighbourReadsOriginalPixelsWithBoundedQuantization(){
        int[] pixels=new Random(517).ints(768*576).toArray();
        var result=CabinetMediaCodec.encodeVideo(frame(768,576,pixels));assertNotNull(result);assertTrue(result.width()<384);
        int[] decoded=CabinetMediaCodec.decodeVideo(result);
        for(int y=0;y<result.height();y++)for(int x=0;x<result.width();x++){
            int original=pixels[(int)((long)y*576/result.height())*768+(int)((long)x*768/result.width())],restored=decoded[y*result.width()+x];
            assertEquals(255,restored>>>24);
            assertTrue(Math.abs((original&255)-(restored&255))<=7);
            assertTrue(Math.abs(((original>>>8)&255)-((restored>>>8)&255))<=3);
            assertTrue(Math.abs(((original>>>16)&255)-((restored>>>16)&255))<=7);
        }
    }
    @Test void highEntropyAtMaximumSourceAndExtremeShapesAlwaysProducesBoundedFrame(){
        for(int[] d:new int[][]{{2048,2048},{2048,1},{1,2048},{384,288},{320,224},{192,144}}){
            var result=CabinetMediaCodec.encodeVideo(frame(d[0],d[1],new Random(71).ints(d[0]*d[1]).toArray()));assertNotNull(result);
            assertTrue(result.width()<=Math.min(d[0],384));assertTrue(result.height()<=Math.min(d[1],288));
            assertTrue(result.data().length<=131072);assertEquals(result.width()*result.height(),CabinetMediaCodec.decodeVideo(result).length);
        }
    }
    @Test void fallbackNeverMutatesPublishedPixelsOrAudio(){
        int[] pixels=new Random(67).ints(384*288).toArray(),before=pixels.clone();short[] pcm={Short.MIN_VALUE,-2,3,Short.MAX_VALUE},audio=pcm.clone();
        assertNotNull(CabinetMediaCodec.encodeVideo(new RetroFrame(384,288,pixels,1,3,pcm)));
        assertArrayEquals(before,pixels);assertArrayEquals(audio,pcm);
    }
    @Test void encodedEnvelopeCopiesInputAndOutput(){
        byte[] zipped=zipped(new byte[2]);var e=envelope(1,1,zipped);byte[] original=zipped.clone();
        zipped[0]^=1;assertArrayEquals(original,e.data());byte[] returned=e.data();returned[0]^=1;
        assertArrayEquals(original,e.data());assertEquals(0xff000000,CabinetMediaCodec.decodeVideo(e)[0]);
    }
    @Test void rejectsInvalidEnvelopeMetadataBeforeDecodeAllocation(){
        byte[] z={1};
        for(int[] dimensions:new int[][]{{0,1},{1,0},{385,1},{1,289},{Integer.MAX_VALUE,Integer.MAX_VALUE}})
            assertThrows(IllegalArgumentException.class,()->envelope(dimensions[0],dimensions[1],z));
        for(float ratio:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0,11})
            assertThrows(IllegalArgumentException.class,()->new CabinetMediaCodec.Encoded(1,1,ratio,0,z));
        for(int rotation:new int[]{-1,4})assertThrows(IllegalArgumentException.class,()->new CabinetMediaCodec.Encoded(1,1,1,rotation,z));
        assertThrows(IllegalArgumentException.class,()->envelope(1,1,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->envelope(1,1,new byte[131073]));
    }
    @Test void rejectsTruncatedStreamsAtEveryByte(){
        byte[] bytes=zipped(new byte[384*288*2]);
        for(int n=1;n<bytes.length;n++){
            byte[] truncated=Arrays.copyOf(bytes,n);
            assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(384,288,truncated)));
        }
    }
    @Test void rejectsTrailingBytesConcatenatedStreamsAndWrongChecksum(){
        byte[] bytes=zipped(new byte[2]);byte[] trailing=Arrays.copyOf(bytes,bytes.length+1);
        assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(1,1,trailing)));
        byte[] concat=Arrays.copyOf(bytes,bytes.length*2);System.arraycopy(bytes,0,concat,bytes.length,bytes.length);
        assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(1,1,concat)));
        bytes[bytes.length-1]^=1;assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(1,1,bytes)));
    }
    @Test void rejectsExpandedBombAndDecodedLengthMismatch(){
        byte[] bomb=zipped(new byte[16*1024*1024]);
        assertTrue(bomb.length<CabinetMediaCodec.MAX_COMPRESSED_BYTES);
        assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(1,1,bomb)));
        assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(2,1,zipped(new byte[2]))));
        assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(1,1,zipped(new byte[4]))));
    }
    @Test void rejectsDictionaryAndRandomMalformedStreams(){
        Deflater d=new Deflater(1);byte[] dictionary;
        try{d.setDictionary(new byte[]{1,2,3});d.setInput(new byte[]{1,2});d.finish();byte[] temp=new byte[128];dictionary=Arrays.copyOf(temp,d.deflate(temp));}
        finally{d.end();}
        assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(1,1,dictionary)));
        Random random=new Random(88);for(int n=0;n<200;n++){byte[] bytes=new byte[1+random.nextInt(128)];random.nextBytes(bytes);
            assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodeVideo(envelope(1,1,bytes)));}
    }
    @Test void pcmRoundTripIncludesBothChannelsSignAndLittleEndian(){
        short[] values={Short.MIN_VALUE,Short.MAX_VALUE,-1,0,0x1234,(short)0xabcd};
        byte[] bytes=CabinetMediaCodec.encodePcm(values,0,values.length);
        assertArrayEquals(new byte[]{0,(byte)128,(byte)255,127,(byte)255,(byte)255,0,0,0x34,0x12,(byte)0xcd,(byte)0xab},bytes);
        assertArrayEquals(values,CabinetMediaCodec.decodePcm(bytes));
        assertArrayEquals(new short[]{-1,0},CabinetMediaCodec.decodePcm(CabinetMediaCodec.encodePcm(values,2,2)));
        assertEquals(0,CabinetMediaCodec.decodePcm(new byte[0]).length);
    }
    @Test void pcmMaximumAndManyBlocksPreserveExactSamples(){
        short[] data=new short[32768];Random random=new Random(78);for(int i=0;i<data.length;i++)data[i]=(short)random.nextInt();
        short[] restored=new short[data.length];
        for(int offset=0;offset<data.length;offset+=9600){int count=Math.min(9600,data.length-offset);
            byte[] encoded=CabinetMediaCodec.encodePcm(data,offset,count);assertTrue(encoded.length<=19200);
            System.arraycopy(CabinetMediaCodec.decodePcm(encoded),0,restored,offset,count);}
        assertArrayEquals(data,restored);
    }
    @Test void pcmRejectsMisalignmentOverflowAndOversize(){
        short[] data=new short[10000];
        for(int[] slice:new int[][]{{-1,2},{0,-2},{1,2},{0,1},{0,9602},{9998,4},{Integer.MAX_VALUE,2},{0,Integer.MAX_VALUE}})
            assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.encodePcm(data,slice[0],slice[1]));
        for(int n:new int[]{1,2,3,19199,19204})assertThrows(IllegalArgumentException.class,()->CabinetMediaCodec.decodePcm(new byte[n]));
    }
}
