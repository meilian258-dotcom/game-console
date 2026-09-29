package cn.piq.computer.client;
import cn.piq.pvz.runtime.PvzRuntime;
import cn.piq.pvz.runtime.PvzEngine;
import cn.piq.pvz.runtime.PvzJniRuntime;
import java.nio.file.Path;
import java.util.*;
/** Optional PvZ10 adapter. Process mode remains the default; JNI requires local opt-in. */
final class PvzComputerBackend implements ProgramBackend {
    private final PvzEngine runtime;
    private final Set<Integer> held=new HashSet<>();
    private int pad,x,y,buttons;
    private boolean onScreen;
    private static final Map<Integer,Integer> PAD=Map.of(257,2,258,3,263,6,262,7,265,4,264,5,74,0,75,8,81,10,69,11);
    PvzComputerBackend(Path root,Path file,UUID player)throws Exception{this(root,file,player,false);}
    PvzComputerBackend(Path root,Path file,UUID player,boolean jni)throws Exception{runtime=jni?new PvzJniRuntime(root,file,player):new PvzRuntime(root,file,player);}
    public int width(){return 800;}public int height(){return 600;}
    public byte[] frame(){return runtime.poll();}public void releaseFrame(byte[] data){runtime.releaseFrame(data);}
    private void send(){runtime.input(pad,x,y,onScreen?buttons:0,onScreen);}
    public void pointer(int x,int y,int buttons,boolean screen){this.x=(int)(Math.clamp(x/639.0,0,1)*65535)-32768;this.y=(int)(Math.clamp(y/479.0,0,1)*65535)-32768;this.buttons=buttons&3;onScreen=screen;send();}
    public void key(int key,boolean down){
        if(down)held.add(key);else held.remove(key);
        pad=0;for(var k:held)if(PAD.containsKey(k))pad|=1<<PAD.get(k);send();
        if(key==259)runtime.key(down,8,0);
    }
    public void text(int codepoint){if(codepoint>=32&&codepoint<127)runtime.key(true,0,codepoint);}
    public void scroll(int direction){/* Wheel is reserved for future backends; PvZ uses Q/E. */}
    public void pause(boolean v){runtime.pause(v);}public void volume(float v){runtime.volume(v);}
    public boolean ready(){return runtime.ready();}public boolean finished(){return runtime.finished();}
    public String status(){return runtime.status();}public String error(){return runtime.error();}
    public void releaseInput(){for(var k:Set.copyOf(held))key(k,false);held.clear();pad=buttons=0;onScreen=false;runtime.input(0,0,0,0,false);runtime.key(false,8,0);}
    public void close(){releaseInput();runtime.close();}
    public void audioSink(AudioSink sink){runtime.audioSink(sink==null?null:pcm->sink.pcm(pcm,44100,2));}
}
