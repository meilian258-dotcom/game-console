package cn.piq.computer.client;

import cn.piq.computer.flash.FlashRuntime;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Thin adapter over the frozen Flash 0.1.2 IPC bridge. No server paths, downloads or global input. */
public final class FlashComputerBackend implements ProgramBackend {
    private final FlashRuntime runtime;
    private final Set<Integer> keys=new HashSet<>();
    private boolean paused,closed,exitConfirmed;
    private int lastX=320,lastY=240,lastButtons=-1,lastP1=-1,lastP2=-1;
    public FlashComputerBackend(Path root,Path file) throws Exception {runtime=new FlashRuntime(root,file);}
    @Override public int width(){return 640;}
    @Override public int height(){return 480;}
    @Override public byte[] frame(){var f=runtime.poll();if(f==null)return null;int[] p=f.abgr();var bytes=new byte[p.length*4];for(int i=0;i<p.length;i++){int n=p[i];int at=i*4;bytes[at]=(byte)n;bytes[at+1]=(byte)(n>>>8);bytes[at+2]=(byte)(n>>>16);bytes[at+3]=(byte)(n>>>24);}return bytes;}
    @Override public void releaseFrame(byte[] bytes){}
    @Override public void pointer(int x,int y,int buttons,boolean inside){int mask=inside?buttons&1:0;if(!inside){x=lastX;y=lastY;}if(x==lastX&&y==lastY&&mask==lastButtons)return;lastX=x;lastY=y;lastButtons=mask;runtime.mouse(x,y,mask!=0);}
    @Override public void key(int key,boolean down){if(down)keys.add(key);else keys.remove(key);int p1=mask(keys,263,262,265,264,32),p2=mask(keys,65,68,87,83,340);if(p1!=lastP1||p2!=lastP2){lastP1=p1;lastP2=p2;runtime.keys(p1,p2);}}
    public static int mask(Set<Integer> held,int left,int right,int up,int down,int action){int m=0;int[] map={left,right,up,down,action};for(int i=0;i<map.length;i++)if(held.contains(map[i]))m|=1<<i;return m;}
    @Override public void text(int codepoint){} // Frozen helper exposes two five-key groups, not arbitrary text input.
    @Override public void scroll(int direction){}
    @Override public void pause(boolean value){if(paused==value)return;paused=value;if(value){releaseInput();runtime.pause();}else runtime.resume();}
    @Override public void volume(float value){} // WebView audio is local system output; do not claim MC volume support.
    @Override public boolean ready(){return runtime.ready();}
    @Override public boolean finished(){return runtime.finished();}
    @Override public String status(){return !runtime.audioError().isEmpty()?runtime.audioError():runtime.ready()?"Flash · 收图 "+runtime.captureFps()+" FPS":runtime.status();}
    public void audioSink(AudioSink sink){runtime.audioSink(sink==null?null:pcm->sink.pcm(pcm,24000,1));}
    @Override public String error(){return closed?(exitConfirmed?"":"Flash 运行器退出尚未确认"):runtime.finished()?runtime.status():"";}
    @Override public void releaseInput(){keys.clear();lastP1=lastP2=0;runtime.keys(0,0);lastButtons=0;runtime.mouse(lastX,lastY,false);}
    @Override public void close(){closed=true;releaseInput();runtime.close();exitConfirmed=runtime.awaitExit();}
}
