// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.runtime;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sound.sampled.*;

/** Pinned executable, private pipes, independent persistent saves. Not an OS security sandbox. */
public final class PvzRuntime implements PvzEngine {
    public static final int WIDTH=800, HEIGHT=600;
    public static final String CORE_SHA="7E4CAA5F801CF9B0FDB03E3E46D704447A49AA2198DC20A1E8369D73C303AD08";
    public static final String HOST_SHA="190E520A1D36CC92F9A39E83D56AF1C287CC284E78ACC42A1E839953252895AF";
    private final FrameMailbox image=new FrameMailbox(WIDTH*HEIGHT*4);
    private final ArrayBlockingQueue<byte[]> audio=new ArrayBlockingQueue<>(12);
    private final ArrayBlockingQueue<byte[]> commands=new ArrayBlockingQueue<>(128);
    private final AtomicReference<byte[]> controls=new AtomicReference<>();
    private final AtomicBoolean closing=new AtomicBoolean();
    private volatile boolean ready,paused,closed;
    private volatile float volume=.7f;
    private volatile String error="";
    private volatile String audioError="";
    private volatile java.util.function.Consumer<byte[]> audioSink;
    /** Bounded/nonblocking consumer; receives its own pre-volume 44.1 kHz stereo PCM copy. */
    public void audioSink(java.util.function.Consumer<byte[]> sink){audioSink=sink;}
    private Process process;
    private Path directory,save;
    private FileChannel lockChannel; private FileLock lock;
    private volatile SourceDataLine sound;
    private volatile long frames;
    private volatile double receivedFps,coreCalls;

    public PvzRuntime(Path game,Path content,UUID player) throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows") || !Set.of("amd64","x86_64").contains(System.getProperty("os.arch")))
            throw new IOException("此测试运行器需要 Windows 64 位");
        if(content.isAbsolute())content=cn.piq.retro.storage.ConsoleStorage.rebind(game,content);
        if(!content.isAbsolute() || !content.getFileName().toString().equalsIgnoreCase("main.pak") || !Files.isRegularFile(content,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("请选择本机 main.pak 文件的完整路径");
        try {
            directory=Files.createTempDirectory("piq-pvz-");
            Path staged=Files.createDirectory(directory.resolve("data"));
            copyBounded(content,staged.resolve("main.pak"),64L*1024*1024);
            String contentHash=sha(staged.resolve("main.pak"));
            Path props=content.getParent().resolve("properties");
            if(Files.isDirectory(props,LinkOption.NOFOLLOW_LINKS)) {
                Path target=Files.createDirectory(staged.resolve("properties"));
                for(String name:List.of("default.xml","Layout.xml","partner.xml","partner.xml.sig","partner_logo.jpg")) {
                    Path from=props.resolve(name);
                    if(Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS))copyBounded(from,target.resolve(name),4L*1024*1024);
                }
            }
            save=cn.piq.retro.storage.ConsoleStorage.root(game.toAbsolutePath()).resolve("piq-pvz/saves").resolve(player.toString()).resolve(contentHash);
            Files.createDirectories(save);
            lockChannel=FileChannel.open(save.resolve("session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try{lock=lockChannel.tryLock();}catch(OverlappingFileLockException e){throw new IOException("这份 PvZ 存档正在使用中",e);}
            if(lock==null)throw new IOException("这份 PvZ 存档正在使用中");
            Path host=extract("piq-pvz-host.exe",HOST_SHA),core=extract("pvz_libretro.dll",CORE_SHA);
            Path logs=cn.piq.retro.storage.ConsoleStorage.root(game.toAbsolutePath()).resolve("piq-pvz/logs");Files.createDirectories(logs);
            Path log=logs.resolve("pvz-"+System.currentTimeMillis()+".log");
            process=new ProcessBuilder(host.toString(),core.toString(),staged.toString(),save.toString()).directory(directory.toFile()).redirectError(log.toFile()).start();
            daemon("PvZ-Video",this::read);
            daemon("PvZ-Input",this::write);
            daemon("PvZ-Audio",this::playAudio);
            daemon("PvZ-StartupGuard",()->{try{Thread.sleep(45000);if(!ready&&!closing.get()){error="加载超时，请检查 main.pak 与运行器日志";close();}}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        } catch(Exception e) {close();throw e;}
    }
    public static void copyBounded(Path from,Path to,long max) throws IOException {
        long size=Files.size(from);if(size<1||size>max)throw new IOException("文件大小超出限制："+from.getFileName());
        try(InputStream in=Files.newInputStream(from);OutputStream out=Files.newOutputStream(to,StandardOpenOption.CREATE_NEW)) {
            byte[] buffer=new byte[65536];long total=0;int n;
            while((n=in.read(buffer))>=0){total+=n;if(total>max)throw new IOException("文件读取超出限制");out.write(buffer,0,n);}
        }
    }
    public static String sha(Path path) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}
        return HexFormat.of().withUpperCase().formatHex(d.digest());
    }
    private Path extract(String name,String expected) throws Exception {
        Path path=directory.resolve(name);
        try(InputStream in=PvzRuntime.class.getResourceAsStream("/core/pvz/"+name)){
            if(in==null)throw new IOException("附属缺少运行器："+name);
            Files.copy(in,path);
        }
        if(!sha(path).equals(expected))throw new IOException("内置运行器校验失败："+name);
        return path;
    }
    private static void daemon(String name,Runnable task){Thread t=new Thread(task,name);t.setDaemon(true);t.start();}
    public static int[] header(byte[] bytes) throws IOException {
        if(bytes.length!=24)throw new IOException("画面头不完整");
        IntBuffer v=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();int[] h=new int[6];v.get(h);
        if(h[0]!=0x315A5650 || !(h[2]==0&&h[3]==0&&h[4]==0 || h[2]==WIDTH&&h[3]==HEIGHT&&h[4]==WIDTH*HEIGHT*4)
                ||h[5]<0||h[5]>16384||(h[5]&1)!=0)throw new IOException("运行器返回了无效画面/音频");
        return h;
    }
    private void read() {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(process.getInputStream(),2*1024*1024))) {
            byte[] headerBytes=new byte[24];long window=System.nanoTime(),windowFrames=0,previousSequence=0;boolean measured=false;
            while(true){in.readFully(headerBytes);int[] h=header(headerBytes);byte[] picture=h[4]==0?null:image.acquire();
                if(h[4]>0){if(picture==null)in.skipNBytes(h[4]);else try{in.readFully(picture);}catch(IOException e){image.release(picture);throw e;}}
                byte[] pcm=new byte[h[5]*2];in.readFully(pcm);
                if(picture!=null){image.publish(picture);ready=true;frames++;}
                if(!paused&&pcm.length>0){var sink=audioSink;if(sink!=null)try{sink.accept(pcm.clone());}catch(RuntimeException ignored){}if(!audio.offer(pcm)){audio.poll();audio.offer(pcm);}}
                long now=System.nanoTime(),sequence=Integer.toUnsignedLong(h[1]);
                if(!measured){window=now;windowFrames=frames;previousSequence=sequence;measured=true;}
                if(now-window>=1_000_000_000L){double seconds=(now-window)/1e9;receivedFps=(frames-windowFrames)/seconds;coreCalls=((sequence-previousSequence)&0xffffffffL)/seconds;window=now;windowFrames=frames;previousSequence=sequence;}
            }
        } catch(Exception e){if(!closing.get()){error="运行器已停止："+e.getMessage()+"，请查看 game-console/piq-pvz/logs";daemon("PvZ-Failed",this::close);}}
    }
    private void write() {
        try(OutputStream out=new BufferedOutputStream(process.getOutputStream())) {
            while(process.isAlive()) {
                byte[] data=commands.poll(8,TimeUnit.MILLISECONDS);
                if(data!=null)out.write(data);
                if(closing.get()){out.write(command(0,0,0,0,0));out.flush();return;}
                byte[] control=controls.getAndSet(null);if(control!=null)out.write(control);
                if(data!=null||control!=null)out.flush();
            }
        }catch(Exception e){if(!closing.get())error="输入连接中断："+e.getMessage();}
    }
    public static byte[] command(int op,int a,int b,int c,int d){return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0x315A5650).putInt(op).putInt(a).putInt(b).putInt(c).putInt(d).array();}
    public void input(int pad,int x,int y,int buttons,boolean pointer){if(!closing.get())controls.set(command(1,pad,x,y,buttons|(pointer?4:0)));}
    public void key(boolean down,int key,int character){if(!closing.get())commands.offer(command(3,down?1:0,key,character,0));}
    public void pause(boolean value){if(paused!=value){paused=value;controls.set(command(1,0,0,0,0));commands.offer(command(2,value?1:0,0,0,0));audio.clear();SourceDataLine s=sound;if(s!=null)s.flush();}}
    public void volume(float v){volume=Math.max(0,Math.min(1,v));}
    private void playAudio() {
        try {
            sound=AudioSystem.getSourceDataLine(new AudioFormat(44100,16,2,true,false));sound.open(new AudioFormat(44100,16,2,true,false),11760);sound.start();
            while(!closing.get()){byte[] pcm=audio.poll(100,TimeUnit.MILLISECONDS);if(pcm==null||paused)continue;
                float gain=volume;for(int i=0;i<pcm.length;i+=2){short s=(short)((pcm[i]&255)|(pcm[i+1]<<8));int scaled=(int)(s*gain);pcm[i]=(byte)scaled;pcm[i+1]=(byte)(scaled>>8);}sound.write(pcm,0,pcm.length);
            }
        }catch(Exception e){if(!closing.get())audioError="音频输出不可用："+e.getMessage();}
        finally{SourceDataLine s=sound;if(s!=null){s.stop();s.close();}}
    }
    /** Consumer must return each polled frame exactly once, after upload/use completes. */
    public byte[] poll(){return image.poll();}
    public void releaseFrame(byte[] frame){image.release(frame);}
    public boolean ready(){return ready&&!closed;}
    public boolean finished(){return closed;}
    public String error(){return error;}
    public String status(){return !error.isEmpty()?error:!audioError.isEmpty()?audioError:paused?"已暂停":ready?"本机单人 · 核心调用 "+Math.round(coreCalls)+"/s · 收图 "+Math.round(receivedFps)+" FPS":"正在加载游戏…";}
    public Path saveDirectory(){return save;}
    @Override public void close() {
        if(!closing.compareAndSet(false,true))return;
        audioSink=null;
        try {
            if(process!=null&&!process.waitFor(12,TimeUnit.SECONDS)) {error="运行器未正常退出；最近进度可能未保存，请保留日志。";process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);}
        }catch(InterruptedException e){Thread.currentThread().interrupt();if(process!=null)process.destroyForcibly();error="退出被中断，最近进度未确认。";}
        finally {
            if(process!=null&&(process.isAlive()||process.exitValue()!=0)&&error.isEmpty())error="运行器异常退出，进度未确认。";
            SourceDataLine s=sound;if(s!=null)s.close();audio.clear();
            try{if(lock!=null)lock.release();if(lockChannel!=null)lockChannel.close();}catch(IOException ignored){}
            // Only this instance's generated staging directory; persistent saves and original data are never removed.
            if(directory!=null)try(var walk=Files.walk(directory)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())try{Files.deleteIfExists(p);}catch(IOException ignored){}}catch(IOException ignored){}
            closed=true;
        }
    }
}
