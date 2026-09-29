package cn.piq.flashbox.runtime;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameImageDecoderTest {
    @Test void reusedMemoryDecoderKeepsPixelsAndAbgrOrder() throws Exception {
        var image=new BufferedImage(640,480,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2,3,0x804080c0);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);
        try(var decoder=new FrameImageDecoder()) {
            for(int n=0;n<3;n++) {
                int[] pixels=decoder.decode(bytes.toByteArray(),"png");
                assertEquals(640*480,pixels.length);assertEquals(0x80c08040,pixels[3*640+2]);
            }
        }
    }
    @Test void wrongDimensionsAreRejected() throws Exception {
        var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);
        try(var decoder=new FrameImageDecoder()) {
            assertThrows(java.io.IOException.class,()->decoder.decode(bytes.toByteArray(),"png"));
        }
    }
    @Test void jpegReaderCanBeReusedWithoutLosingColorChannels() throws Exception {
        var image=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(new java.awt.Color(220,55,25));graphics.fillRect(0,0,640,480);graphics.dispose();
        var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"JPEG",bytes));
        try(var decoder=new FrameImageDecoder()) {
            for(int n=0;n<3;n++) {
                int pixel=decoder.decode(bytes.toByteArray(),"jpeg")[100*640+100];
                assertEquals(255,pixel>>>24);
                assertTrue(Math.abs((pixel&255)-220)<8);
                assertTrue(Math.abs(((pixel>>>8)&255)-55)<8);
                assertTrue(Math.abs(((pixel>>>16)&255)-25)<8);
            }
        }
    }
    @Test void jpegDimensionsAndTruncationAreRejectedBeforeDecode() throws Exception {
        var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"JPEG",bytes);
        try(var decoder=new FrameImageDecoder()) {
            assertThrows(java.io.IOException.class,()->decoder.decode(bytes.toByteArray(),"jpeg"));
            assertThrows(java.io.IOException.class,()->decoder.decode(new byte[]{(byte)255,(byte)216},"jpeg"));
            assertThrows(java.io.IOException.class,()->decoder.decode(bytes.toByteArray(),"webp"));
        }
    }
    @Test void readersAlternateFormatsAndRecoverAfterRejectedFrame() throws Exception {
        var image=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);
        var png=new ByteArrayOutputStream();var jpeg=new ByteArrayOutputStream();
        ImageIO.write(image,"PNG",png);ImageIO.write(image,"JPEG",jpeg);
        try(var decoder=new FrameImageDecoder()) {
            for(int n=0;n<3;n++) {
                assertEquals(640*480,decoder.decode(png.toByteArray(),"png").length);
                assertThrows(java.io.IOException.class,()->decoder.decode(png.toByteArray(),"jpeg"));
                assertThrows(java.io.IOException.class,()->decoder.decode(jpeg.toByteArray(),"png"));
                assertEquals(640*480,decoder.decode(jpeg.toByteArray(),"jpeg").length);
            }
        }
    }
    @Test void validJpegCannotBypassTruncationLengthOrHugeDimensionChecks() throws Exception {
        var image=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);
        var encoded=new ByteArrayOutputStream();ImageIO.write(image,"JPEG",encoded);
        byte[] valid=encoded.toByteArray(),truncated=java.util.Arrays.copyOf(valid,valid.length-2);
        byte[] brokenLength=valid.clone();brokenLength[4]=0;brokenLength[5]=1;
        byte[] huge=valid.clone();boolean changed=false;
        for(int i=2;i<huge.length-10;i++)if((huge[i]&255)==255&&(huge[i+1]&255)==192) {
            huge[i+7]=(byte)255;huge[i+8]=(byte)255;changed=true;break;
        }
        assertTrue(changed);
        try(var decoder=new FrameImageDecoder()) {
            for(byte[] bad:new byte[][]{truncated,brokenLength,huge})
                assertThrows(java.io.IOException.class,()->decoder.decode(bad,"jpeg"));
            assertEquals(640*480,decoder.decode(valid,"jpeg").length);
        }
    }
}
