package cn.piq.computer.stream;

import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.*;
import javax.imageio.*;
import javax.imageio.stream.*;

/** Fixed dimensions, bounded compressed size; never trust a JPEG's allocation dimensions. */
public final class StreamImage {
    /** Reserve PCM, part headers and scheduling jitter; do not raise the selected network ceiling. */
    public static int frameBudget(int tier){return (StreamBudget.RATES[Math.clamp(tier,0,2)]-32768)*85/100/15-384;}
    public static byte[] encode(byte[] rgba,int width,int height)throws IOException{
        return new Encoder(StreamPart.MAX_IMAGE).encode(rgba,width,height);
    }
    /** Session-owned feedback avoids doing several trial encodes on every frame. */
    public static final class Encoder {
        private final int limit;
        private float quality=.65f;
        private int scale=1,frames;
        public Encoder(int limit){if(limit<2048||limit>StreamPart.MAX_IMAGE)throw new IllegalArgumentException("JPEG budget");this.limit=limit;}
        public byte[] encode(byte[] rgba,int width,int height)throws IOException{
        if(width<1||height<1||width>1024||height>1024||rgba.length!=width*height*4)throw new IOException("Frame bounds");
        var src=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        int[] rgb=new int[width*height];for(int i=0;i<rgb.length;i++)rgb[i]=((rgba[i*4]&255)<<16)|((rgba[i*4+1]&255)<<8)|(rgba[i*4+2]&255);
        src.setRGB(0,0,width,height,rgb,0,width);
        var image=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(src,0,0,640,480,null);}finally{g.dispose();}
        if(++frames%30==0){if(scale>1)scale/=2;else quality=Math.min(.65f,quality+.03f);}
        // Keep the fixed wire dimensions. Extremely detailed frames can trade fine detail for
        // cadence; receivers need no protocol change and still enforce the 640x480 allocation.
        for(int attempt=0;attempt<12;attempt++){
            BufferedImage candidate=image;
            if(scale>1){var small=new BufferedImage(640/scale,480/scale,BufferedImage.TYPE_INT_RGB);var down=small.createGraphics();try{down.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);down.drawImage(image,0,0,small.getWidth(),small.getHeight(),null);}finally{down.dispose();}
                candidate=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);var up=candidate.createGraphics();try{up.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);up.drawImage(small,0,0,640,480,null);}finally{up.dispose();}}
            var bytes=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("jpeg").next();
            try(var out=new MemoryCacheImageOutputStream(bytes)){writer.setOutput(out);var options=writer.getDefaultWriteParam();options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);options.setCompressionQuality(quality);if(options instanceof javax.imageio.plugins.jpeg.JPEGImageWriteParam jpeg)jpeg.setOptimizeHuffmanTables(true);writer.write(null,new IIOImage(candidate,null,null),options);}finally{writer.dispose();}
            if(bytes.size()<=limit)return bytes.toByteArray();
            if(quality>.065f)quality=Math.max(.06f,quality*(float)Math.pow((double)limit/bytes.size(),1.4)*.9f);
            else {if(scale>=16)break;scale*=2;quality=.18f;}
        }
        return null; // Drop difficult frames instead of violating the network budget.
        }
    }
    public static int[] decode(byte[] jpeg)throws IOException{
        if(jpeg.length<4||jpeg.length>StreamPart.MAX_IMAGE)throw new IOException("JPEG bounds");
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Not JPEG");var reader=readers.next();
            try{reader.setInput(input,true,true);if(!reader.getFormatName().equalsIgnoreCase("JPEG")||reader.getWidth(0)!=640||reader.getHeight(0)!=480)throw new IOException("JPEG dimensions");
                var image=reader.read(0);int[] pixels=image.getRGB(0,0,640,480,null,0,640);for(int i=0;i<pixels.length;i++){int p=pixels[i];pixels[i]=0xff000000|((p&255)<<16)|(p&0xff00)|((p>>>16)&255);}return pixels;
            }finally{reader.dispose();}
        }
    }
    private StreamImage(){}
}
