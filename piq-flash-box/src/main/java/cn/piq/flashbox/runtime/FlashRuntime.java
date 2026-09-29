package cn.piq.flashbox.runtime;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local experimental WebView helper. No network socket and no Minecraft packet sender. */
public final class FlashRuntime implements AutoCloseable {
    public record Frame(long sequence, int[] abgr) {}
    private final Process process;
    private final ArrayBlockingQueue<String> commands = new ArrayBlockingQueue<>(128);
    private final AtomicReference<Frame> frame = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String status = "运行器准备中…";
    private volatile boolean ready;
    private volatile long lastFrameAt;
    private volatile int captureFps;

    public FlashRuntime(Path gameDirectory, Path swf) throws Exception { this(gameDirectory,swf,()->false); }
    public FlashRuntime(Path gameDirectory, Path swf, java.util.function.BooleanSupplier cancelled) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
            throw new IOException("此原型运行器暂只支持 Windows x64");
        swf = swf.toRealPath();
        if (!Files.isRegularFile(swf) || !swf.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".swf")
                || Files.size(swf)<8 || Files.size(swf)>32L*1024*1024)
            throw new IOException("请选择不超过 32 MiB 的本机 SWF 文件");
        try (var in=Files.newInputStream(swf)) {
            byte[] header=in.readNBytes(8);
            if ((header[0]!='C' && header[0]!='F' && header[0]!='Z') || header[1]!='W' || header[2]!='S')
                throw new IOException("所选文件不是 SWF");
        }
        Path root=gameDirectory.toAbsolutePath().normalize().resolve("piq-flash-box");
        Path runtime=root.resolve("runtime");
        verifyRuntime(runtime);
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("启动已取消");
        Files.createDirectories(root.resolve("logs"));
        Path log=root.resolve("logs/helper-"+UUID.randomUUID()+".log");
        var command=new ArrayList<>(List.of(runtime.resolve("FlashBox.Helper.exe").toString(),
                "--swf",swf.toString(),"--width","640","--height","480"));
        Path bindings=root.resolve("bindings.json");
        if (Files.isRegularFile(bindings)) command.addAll(List.of("--bindings",bindings.toString()));
        process=new ProcessBuilder(command).directory(runtime.toFile()).redirectError(log.toFile()).start();
        thread("FlashBox-Read",this::readLoop);
        thread("FlashBox-Write",this::writeLoop);
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())close();
        thread("FlashBox-Watchdog",()-> {
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
                while (!closed.get()) {
                    long now=System.nanoTime();
                    if(!process.isAlive() || !ready && now>deadline) { fail("Flash 运行器启动失败或超时，请检查运行环境与日志");break; }
                    if(ready && now-lastFrameAt>TimeUnit.SECONDS.toNanos(12)){fail("Flash 画面通道超时，已安全结束本机预览");break;}
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
    static void verifyRuntime(Path directory) throws Exception {
        try (InputStream resource=FlashRuntime.class.getResourceAsStream("/flash-runtime.sha256")) {
            if (resource==null) throw new IOException("此构建没有经过冻结的 Flash 运行器清单，禁止启动");
            Path real=directory.toRealPath();
            var reader=new BufferedReader(new InputStreamReader(resource,StandardCharsets.UTF_8));
            String line;int count=0;boolean executable=false;
            while ((line=FlashProtocol.line(reader,512))!=null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (++count>100 || !line.matches("[a-fA-F0-9]{64}  [A-Za-z0-9_./-]+")) throw new IOException("运行器清单不合法");
                String name=line.substring(66);
                Path file=real.resolve(name).normalize();
                if (!file.startsWith(real) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(real)
                        || Files.size(file)>128L*1024*1024) throw new IOException("运行器缺少文件或路径不合法："+name);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try (InputStream in=Files.newInputStream(file)) {
                    byte[] block=new byte[65536];int n;while ((n=in.read(block))!=-1) digest.update(block,0,n);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(line.substring(0,64)))
                    throw new IOException("运行器文件与本版不匹配："+name);
                if (name.equals("FlashBox.Helper.exe")) executable=true;
            }
            if (!executable) throw new IOException("运行器清单缺少程序入口");
        }
    }
    private static void thread(String name,Runnable body) { Thread t=new Thread(body,name);t.setDaemon(true);t.start(); }
    public String status() { return status; }
    public int captureFps() { return captureFps; }
    public boolean ready() { return ready && !closed.get(); }
    public Frame poll() { return frame.getAndSet(null); }
    public void keys(int p1,int p2) { command(FlashProtocol.keys(p1,p2)); }
    public void mouse(int x,int y,boolean down) { command(FlashProtocol.mouse(x,y,down)); }
    public void pause() { command(FlashProtocol.keys(0,0));command("{\"op\":\"pause\"}"); }
    public void resume() { command("{\"op\":\"resume\"}"); }
    private void command(String value) {
        if (!closed.get() && !commands.offer(value)) fail("本机输入队列拥堵，已停止以避免按键滞留");
    }
    private void writeLoop() {
        try (var out=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8))) {
            while (!closed.get()) {
                String command=commands.poll(100,TimeUnit.MILLISECONDS);
                if (command!=null) { out.write(command);out.newLine();out.flush(); }
            }
            out.write("{\"op\":\"keys\",\"p1\":0,\"p2\":0}\n{\"op\":\"close\"}\n");out.flush();
        } catch (Exception e) { if (!closed.get()) fail("Flash 输入通道已关闭"); }
    }
    private void readLoop() {
        long last=-1;
        String format="png";
        long windowAt=System.nanoTime();int windowFrames=0;
        try (var decoder=new FrameImageDecoder();var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8),65536)) {
            String line;
            while (!closed.get() && (line=FlashProtocol.line(reader,FlashProtocol.MAX_LINE))!=null) {
                var message=JsonParser.parseString(line).getAsJsonObject();
                String kind=message.get("kind").getAsString();
                switch(kind) {
                    case "ready" -> {
                        if(ready)throw new IOException("运行器重复就绪消息");
                        format=message.has("format")?message.get("format").getAsString():"png";
                        if(!format.equals("png")&&!format.equals("jpeg"))throw new IOException("运行器画面格式不受支持");
                        lastFrameAt=System.nanoTime();ready=true;status="本机运行中 · 非多人同步 · 声音由运行器输出";
                    }
                    case "frame" -> {
                        if (!ready) throw new IOException("未就绪就收到画面");
                        long seq=message.get("seq").getAsLong();
                        if (seq<=last) throw new IOException("画面序列倒退");
                        if(!message.has(format) || message.has(format.equals("png")?"jpeg":"png"))throw new IOException("运行器画面格式不一致");
                        byte[] bytes=Base64.getDecoder().decode(message.get(format).getAsString());
                        FlashProtocol.checkImage(bytes,format,message.get("width").getAsInt(),message.get("height").getAsInt());
                        int[] pixels=decoder.decode(bytes,format);
                        frame.set(new Frame(seq,pixels));last=seq;lastFrameAt=System.nanoTime();
                        windowFrames++;
                        if(lastFrameAt-windowAt>=TimeUnit.SECONDS.toNanos(1)) {
                            captureFps=(int)Math.round(windowFrames*1_000_000_000.0/(lastFrameAt-windowAt));
                            windowAt=lastFrameAt;windowFrames=0;
                        }
                    }
                    case "error" -> {
                        String error=message.get("message").getAsString();
                        if (error.length()>300) error=error.substring(0,300);
                        if (!message.has("fatal") || message.get("fatal").getAsBoolean()) throw new IOException(error);
                        status=error;
                    }
                    default -> throw new IOException("未知运行器消息");
                }
            }
            if (!closed.get()) fail("Flash 运行器退出，请检查本机日志");
        } catch (Exception e) { if (!closed.get()) fail("Flash 原型错误："+e.getMessage()); }
    }
    private void fail(String message) { status=message;close(); }
    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        ready=false;frame.set(null);commands.clear();
        thread("FlashBox-Close",()-> {
            try { if (!process.waitFor(6500,TimeUnit.MILLISECONDS)) { process.destroy(); if(!process.waitFor(500,TimeUnit.MILLISECONDS)) process.destroyForcibly(); } }
            catch (InterruptedException e) { process.destroyForcibly();Thread.currentThread().interrupt(); }
        });
    }
}
