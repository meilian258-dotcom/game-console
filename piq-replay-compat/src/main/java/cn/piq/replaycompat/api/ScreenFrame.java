package cn.piq.replaycompat.api;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;

/** Immutable owned snapshot. Empty dimensions represent a source-close event. No ROM or core state. */
public record ScreenFrame(String source, String dimension, long timestampMillis,
                          int width, int height, int[] abgr, List<ScreenVertex> vertices) {
    public static final int MAX_PIXELS=1024*1024, MAX_VERTICES=4096;
    public static final long MAX_TIME=86_400_000;
    public ScreenFrame {
        if (source==null || source.isBlank() || source.length()>192 || source.chars().anyMatch(c->c<32)
                || dimension==null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || dimension.length()>128
                || timestampMillis<0 || timestampMillis>MAX_TIME || width<0 || height<0
                || width>1024 || height>1024 || (long)width*height>MAX_PIXELS)
            throw new IllegalArgumentException("Invalid frame metadata");
        Objects.requireNonNull(abgr); Objects.requireNonNull(vertices);
        if (abgr.length!=(long)width*height || vertices.size()>MAX_VERTICES || vertices.size()%4!=0
                || (width==0 || height==0) && (width!=0 || height!=0 || !vertices.isEmpty())
                || width>0 && vertices.isEmpty()) throw new IllegalArgumentException("Invalid frame data");
        abgr=abgr.clone(); vertices=List.copyOf(vertices);
    }
    @Override public int[] abgr(){return abgr.clone();}
    /** Read one owned pixel without exposing the mutable backing array or allocating a copy. */
    public int pixelAbgr(int index){return abgr[index];}
    /** Content-only screening hash; callers must still use samePixels to resolve collisions. */
    public int pixelHash(){return Arrays.hashCode(abgr);}
    /** Pixel identity excludes source, timestamp, dimension and world geometry; no arrays escape. */
    public boolean samePixels(ScreenFrame other){
        return other!=null&&width==other.width&&height==other.height&&Arrays.equals(abgr,other.abgr);
    }
    public boolean closed(){return width==0;}
    public ScreenFrame at(long time){return new ScreenFrame(source,dimension,time,width,height,abgr,vertices);}
    public static ScreenFrame closed(String source,String dimension,long time){
        return new ScreenFrame(source,dimension,time,0,0,new int[0],List.of());
    }
}
