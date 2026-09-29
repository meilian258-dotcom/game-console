package cn.piq.flashbox.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Reusable bounded PNG/JPEG readers; never creates ImageIO disk cache files. */
final class FrameImageDecoder implements AutoCloseable {
    private final ImageReader png=ImageIO.getImageReadersByFormatName("PNG").next();
    private final ImageReader jpeg=ImageIO.getImageReadersByFormatName("JPEG").next();
    int[] decode(byte[] bytes, String format) throws IOException {
        FlashProtocol.checkImage(bytes,format,640,480);
        ImageReader decoder=format.equals("jpeg")?jpeg:png;
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            decoder.setInput(input,true,true);
            if(decoder.getWidth(0)!=640 || decoder.getHeight(0)!=480)throw new IOException("画面尺寸不匹配");
            var image=decoder.read(0);
            if(image.getWidth()!=640 || image.getHeight()!=480)throw new IOException("画面尺寸不匹配");
            int[] pixels=image.getRGB(0,0,640,480,null,0,640);
            for(int i=0;i<pixels.length;i++) {
                int p=pixels[i];pixels[i]=(p&0xff00ff00)|((p>>>16)&255)|((p&255)<<16);
            }
            return pixels;
        } finally { decoder.setInput(null); }
    }
    @Override public void close() { png.dispose();jpeg.dispose(); }
}
