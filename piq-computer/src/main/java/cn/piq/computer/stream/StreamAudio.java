package cn.piq.computer.stream;

/** G.711 mu-law, 24 kHz mono transport (24 KB/s before framing), not lossless music. */
public final class StreamAudio {
    public static final int RATE=24000;
    public static byte encode(int sample){
        int sign=sample<0?0x80:0;int value=Math.min(32635,Math.abs(sample))+132;
        int exponent=7;for(int mask=0x4000;exponent>0&&(value&mask)==0;mask>>=1)exponent--;
        return (byte)~(sign|(exponent<<4)|((value>>(exponent+3))&15));
    }
    public static short decode(byte value){int u=(~value)&255;int t=((u&15)<<3)+132;t<<=(u>>4)&7;return (short)((u&128)!=0?132-t:t-132);}
    public static byte[] pcm(byte[] encoded,float gain){byte[] out=new byte[encoded.length*2];for(int i=0;i<encoded.length;i++){short s=(short)(decode(encoded[i])*Math.clamp(gain,0,1));out[2*i]=(byte)s;out[2*i+1]=(byte)(s>>8);}return out;}
    /** Stateful nearest-sample resampler; phase survives individual native audio chunks. */
    public static final class Resampler {
        private long phase;
        public byte[] convert(byte[] pcm,int rate,int channels){
            if(rate<8000||rate>192000||channels<1||channels>2||pcm.length%(channels*2)!=0)throw new IllegalArgumentException("PCM format");
            var out=new java.io.ByteArrayOutputStream();
            for(int at=0;at<pcm.length;at+=channels*2){
                int a=(short)((pcm[at]&255)|(pcm[at+1]<<8));
                if(channels==2)a=(a+(short)((pcm[at+2]&255)|(pcm[at+3]<<8)))/2;
                phase+=RATE;while(phase>=rate){out.write(encode(a));phase-=rate;}
            }
            return out.toByteArray();
        }
    }
    private StreamAudio(){}
}
