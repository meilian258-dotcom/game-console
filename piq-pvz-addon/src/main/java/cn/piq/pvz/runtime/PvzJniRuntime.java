// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.runtime;
import java.io.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import javax.sound.sampled.*;
import cn.piq.retro.libretro.LibretroJniRuntime;
import cn.piq.retro.libretro.LibretroProfile;
import cn.piq.retro.libretro.LibretroProcess;

/** Opt-in in-process experiment. A native fault may terminate the JVM; never force-stop its worker. */
public final class PvzJniRuntime implements PvzEngine {
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private final PvzJniWorkspace workspace;
    private final FrameMailbox frames=new FrameMailbox(PvzRuntime.WIDTH*PvzRuntime.HEIGHT*4);
    private final PvzJniInputs inputs=new PvzJniInputs();
    private final ArrayBlockingQueue<byte[]> audio=new ArrayBlockingQueue<>(12);
    private final AtomicBoolean closing=new AtomicBoolean();private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile boolean ready,finished,paused,initialized;private volatile float volume=.7f;
    private volatile String error="",audioError="";private volatile Consumer<byte[]> sink;private volatile SourceDataLine sound;
    private volatile double fps,calls,stepMs;
    private final Thread worker;
    private volatile LibretroJniRuntime common;
    public PvzJniRuntime(Path game,Path content,UUID player)throws Exception{
        if(!System.getProperty("os.name","").startsWith("Windows")||!Set.of("amd64","x86_64").contains(System.getProperty("os.arch")))throw new IOException("JNI试验仅支持Windows x64");
        if(!ACTIVE.compareAndSet(false,true))throw new IOException("已有JNI会话运行或未确认退出；必要时重启客户端");
        PvzJniWorkspace prepared=null;
        try{prepared=new PvzJniWorkspace(game,content,player);workspace=prepared;
            worker=new Thread(this::run,"PvZ-JNI-Worker");worker.setDaemon(true);
            Thread watch=new Thread(()->{try{if(!stopped.await(45,TimeUnit.SECONDS)&&!initialized)fail("JNI初始化超时；原生调用无法安全中断，请结束程序并重启客户端");}catch(InterruptedException e){Thread.currentThread().interrupt();}},"PvZ-JNI-Startup-Watch");watch.setDaemon(true);watch.start();
            worker.start();
        }catch(Exception|LinkageError e){stopped.countDown();try{if(prepared!=null)prepared.close();}catch(Exception cleanup){e.addSuppressed(cleanup);}finally{ACTIVE.set(false);}throw e;}
    }
    private void fail(String reason){error=reason;closing.set(true);}
    public static LibretroProfile profile(){return new LibretroProfile("PvZ","pak",true,List.of(1),false,
        Map.of("pvz_draw_cursor","always","pvz_cursor_speed","6","pvz_gamepad_deadzone","0.25","pvz_cheat_keys","disabled"),
        Map.of("windows-x64",new LibretroProfile.Artifact("/core/pvz/pvz_libretro.dll",PvzRuntime.CORE_SHA)));}
    private void run(){boolean safe=true;LibretroJniRuntime core=null;
        try{
            if(closing.get())return;
            core=new LibretroJniRuntime(profile(),PvzRuntime.class,LibretroJniRuntime.WGL_COMPAT|LibretroJniRuntime.POINTER|LibretroJniRuntime.MOUSE|LibretroJniRuntime.KEYBOARD|LibretroJniRuntime.LEGACY_INLINE_OPTIONS);common=core;
            Map<String,Path> content=new TreeMap<>();content.put("main.pak",workspace.data.resolve("main.pak"));
            for(String name:List.of("default.xml","Layout.xml","partner.xml","partner.xml.sig","partner_logo.jpg")){
                Path file=workspace.data.resolve("properties").resolve(name);if(java.nio.file.Files.isRegularFile(file))content.put("properties/"+name,file);
            }
            var info=core.loadFiles("main.pak",content,workspace.save);
            if(info.width()!=800||info.height()!=600||info.sampleRate()!=44100||info.fps()!=60)throw new IOException("PvZ核心音画与程序适配器不匹配");
            initialized=true;
            Thread speaker=new Thread(this::playAudio,"PvZ-JNI-Audio");speaker.setDaemon(true);speaker.start();
            long next=System.nanoTime(),window=next,steps=0,pictures=0,nanos=0;
            while(!closing.get()){
                if(paused){LockSupport.parkNanos(10_000_000);next=window=System.nanoTime();steps=pictures=nanos=0;continue;}
                var input=inputs.drain();long began=System.nanoTime();
                int[] controls=LibretroJniRuntime.neutralInput();controls[0]=input.pad();controls[4]=input.pointer()?0:-1;controls[5]=input.x();controls[6]=input.y();controls[7]=input.buttons();controls[11]=input.pointer()?6:1;
                int[] keys=new int[input.keys().length/3*4];for(int i=0,j=0;i<input.keys().length;i+=3,j+=4){keys[j]=input.keys()[i];keys[j+1]=input.keys()[i+1];keys[j+2]=input.keys()[i+2];}
                var result=core.step(controls,keys,LibretroProcess.VIDEO|LibretroProcess.AUDIO);
                nanos+=System.nanoTime()-began;steps++;
                if(result.info().width()!=800||result.info().height()!=600||result.info().sampleRate()!=44100||core.rotation()!=0)throw new IOException("PvZ动态音画超出适配器合同");
                if(!result.duplicate()){var frame=frames.acquire();if(frame!=null){System.arraycopy(result.rgba(),0,frame,0,frame.length);frames.publish(frame);ready=true;pictures++;}}
                int bytes=result.stereo().length*2;
                if(bytes>0&&!paused){byte[] chunk=new byte[bytes];ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(result.stereo());var target=sink;if(target!=null)try{target.accept(chunk.clone());}catch(RuntimeException ignored){}if(!audio.offer(chunk)){audio.poll();audio.offer(chunk);}}
                long now=System.nanoTime();if(now-window>=1_000_000_000){double seconds=(now-window)/1e9;calls=steps/seconds;fps=pictures/seconds;stepMs=nanos/1e6/steps;window=now;steps=pictures=nanos=0;}
                next+=16_666_667;if(next<now-200_000_000)next=now;LockSupport.parkNanos(Math.max(0,next-System.nanoTime()));
            }
        }catch(Exception|LinkageError e){fail("JNI运行失败："+e.getMessage());}
        finally{
            closing.set(true);sink=null;
            if(core!=null)try{core.close();}catch(Exception|LinkageError e){safe=false;error="JNI退出未确认，请重启客户端："+e.getMessage();}
            var line=sound;if(line!=null)line.close();audio.clear();
            if(safe){try{workspace.close();}catch(IOException e){if(error.isEmpty())error="JNI临时文件未清理："+e.getMessage();}ACTIVE.set(false);}
            finished=true;stopped.countDown();
        }
    }
    private void playAudio(){
        try{var format=new AudioFormat(44100,16,2,true,false);var line=AudioSystem.getSourceDataLine(format);sound=line;
            line.open(format,11760);if(closing.get())return;line.start();
            while(!closing.get()){byte[] pcm=audio.poll(100,TimeUnit.MILLISECONDS);if(pcm==null||paused)continue;float gain=volume;
                for(int i=0;i<pcm.length;i+=2){short s=(short)((pcm[i]&255)|(pcm[i+1]<<8));int value=(int)(s*gain);pcm[i]=(byte)value;pcm[i+1]=(byte)(value>>8);}line.write(pcm,0,pcm.length);}
        }catch(Exception e){if(!closing.get())audioError="音频输出不可用："+e.getMessage();}finally{var line=sound;if(line!=null)line.close();}
    }
    @Override public void input(int pad,int x,int y,int buttons,boolean pointer){if(!closing.get()&&!paused)inputs.input(pad,x,y,buttons,pointer);}
    @Override public void key(boolean down,int key,int character){if(!closing.get()&&!paused&&!inputs.key(down,key,character))fail("JNI输入队列已满或键值无效");}
    @Override public void pause(boolean value){if(paused!=value){paused=value;inputs.release();audio.clear();var line=sound;if(line!=null&&line.isOpen())line.flush();}}
    @Override public void volume(float value){volume=Float.isFinite(value)?Math.clamp(value,0,1):0;}
    @Override public void audioSink(Consumer<byte[]> consumer){sink=consumer;}
    @Override public byte[] poll(){return frames.poll();}
    @Override public void releaseFrame(byte[] frame){frames.release(frame);}
    @Override public boolean ready(){return ready&&!finished&&error().isEmpty();}
    @Override public boolean finished(){return finished;}
    @Override public String error(){var runtime=common;return !error.isEmpty()?error:runtime==null?"":runtime.diagnosticError();}
    @Override public Path saveDirectory(){return workspace.save;}
    @Override public String status(){String failure=error();return !failure.isEmpty()?failure:!audioError.isEmpty()?audioError:paused?"JNI试验 · 已暂停":ready?"JNI试验 · 独立试验档 · 核心 "+Math.round(calls)+"/s · 收图 "+Math.round(fps)+" FPS · 单步含抓图 "+String.format(Locale.ROOT,"%.2f",stepMs)+"ms":"JNI试验 · 正在加载（原生故障可能导致MC退出）";}
    @Override public void close(){closing.set(true);sink=null;inputs.release();if(Thread.currentThread()==worker)return;
        try{if(!stopped.await(12,TimeUnit.SECONDS))error="JNI未在12秒内退出；无法安全强杀，请保存MC世界后重启客户端";}catch(InterruptedException e){Thread.currentThread().interrupt();error="JNI退出等待被中断，请确认退出或重启客户端";}
    }
}
