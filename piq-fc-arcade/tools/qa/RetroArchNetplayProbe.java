// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.storage.RuntimeWorkspace;
import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import java.io.*;
import java.net.JarURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Isolated real HOST/P2/SPECTATOR acceptance probe, not a Minecraft or performance test.
 * Run with this class directory and one fixed production JAR on the classpath:
 * java ... cn.piq.fcarcade.netplay.RetroArchNetplayProbe NEW_OUTPUT_DIR [P2_DELAY_MS]
 * --self-test runs only parser/ROM checks and does not load any native runtime.
 * --restore-diagnostic NEW_OUTPUT_DIR compares the same unmodified packaged core's
 * exact restore/replay in isolated JNI sessions, with/without controller reads.
 * That diagnostic is not RetroArch acceptance and never changes its CRC gate.
 * No external ROM, native override, core patch, port opening or existing output is accepted.
 * Delay is ordered one-way P2-to-host transport delay, not packet loss or a WAN simulation.
 */
public final class RetroArchNetplayProbe {
    private static final String RUNNER_SHA="1335c34b04d8c3e55afe010a421ff373d938929ec196a6e93a9657972fe034e1";
    private static final String CORE_SHA="a04f7fa28bdd46f602bb62750ab9b5e360d8e8d8ece97de8ad3ef1b669257ffd";
    private static final NetplayProfile PROFILE=FcRetroarchPad.profile();
    private static final int FC_STATE_BYTES=13758;
    private static final Pattern CRC=Pattern.compile("(?m)^\\[INFO] \\[PIQ POC CRC] frame=(\\d+) crc=([0-9a-f]{8}) server=([01]) mode=(\\d+)\\r?\\n");
    private static final AtomicInteger checks=new AtomicInteger();
    private static final Object HOST=new Object(),PLAYER=new Object(),OBSERVER=new Object();
    private static Path root,productionJar;
    private static String originalJarSha;
    private static int delayMillis;
    private static final byte[] ROM=rom();
    private static final List<Run> allRuns=new ArrayList<>();
    private static final Set<Path> knownWorkspaces=new HashSet<>();

    private static void check(boolean ok,String label) {
        if(!ok)throw new AssertionError(label);
        checks.incrementAndGet();System.out.println("CHECK "+label);
    }
    private static String sha(Path path)throws IOException {
        try(var in=Files.newInputStream(path)) {
            try {var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
                while((n=in.read(b))!=-1)digest.update(b,0,n);return HexFormat.of().formatHex(digest.digest());
            }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    private static void writeNew(Path path,String text)throws IOException {Files.writeString(path,text,StandardOpenOption.CREATE_NEW);}
    private static void append(Path path,String text)throws IOException {Files.writeString(path,text,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}

    /** Original diagnostic NROM: independent A buttons select four palette colours.
     * SRAM[0]=0x5a; SRAM[1]/[2]=actual P1/P2 A; SRAM[3] increments every vblank.
     * The pulse's length-counter halt bit keeps the diagnostic tone audible after save/reopen.
     * Nothing is read from a player's game library.
     */
    private static byte[] rom() {return rom(true);}
    private static byte[] rom(boolean readControllers) {
        var a=new Assembler();
        a.emit(0x78,0xd8,0xa2,0xff,0x9a,0xa9,0,0x8d,0,0x20,0x8d,1,0x20);
        a.emit(0xa9,0x5a,0x8d,0,0x60,0xa9,0x40,0x8d,0x17,0x40);
        a.emit(0xa9,1,0x8d,0x15,0x40,0xa9,0xbf,0x8d,0,0x40,0xa9,0xfd,0x8d,2,0x40,0xa9,0x08,0x8d,3,0x40);
        a.label("loop");a.emit(0x2c,2,0x20);a.branch(0x10,"loop");
        a.emit(0xa9,1,0x8d,0x16,0x40,0xa9,0,0x8d,0x16,0x40);
        // LDA abs (4 cycles) becomes LDA #0 + NOP (also 4), retaining all other code.
        if(readControllers)a.emit(0xad,0x16,0x40);else a.emit(0xa9,0,0xea);
        a.emit(0x29,1,0x8d,1,0x60,0x85,0);
        if(readControllers)a.emit(0xad,0x17,0x40);else a.emit(0xa9,0,0xea);
        a.emit(0x29,1,0x8d,2,0x60,0x0a,0x05,0,0xaa);
        a.absolute(0xbd,"colours");a.emit(0x85,1,0xad,2,0x20,0xa9,0x3f,0x8d,6,0x20,0xa9,0,0x8d,6,0x20);
        a.emit(0xa5,1,0x8d,7,0x20,0xa9,0,0x8d,6,0x20,0x8d,6,0x20,0xee,3,0x60);
        a.absolute(0x4c,"loop");a.label("colours");a.emit(0x0f,0x16,0x2a,0x30);
        byte[] code=a.finish(),rom=new byte[16+16384+8192];
        rom[0]='N';rom[1]='E';rom[2]='S';rom[3]=26;rom[4]=1;rom[5]=1;rom[6]=2;rom[8]=1;
        System.arraycopy(code,0,rom,16,code.length);
        for(int i=0x3ffa;i<0x4000;i+=2){rom[16+i]=0;rom[16+i+1]=(byte)0x80;}
        return rom;
    }
    private static final class Assembler {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final Map<String,Integer> labels=new HashMap<>();final List<Object[]> refs=new ArrayList<>();
        void emit(int... values){for(int v:values)bytes.write(v);}
        void label(String name){if(labels.put(name,bytes.size())!=null)throw new AssertionError("Duplicate ROM label");}
        void branch(int op,String label){emit(op);refs.add(new Object[]{bytes.size(),label,true});emit(0);}
        void absolute(int op,String label){emit(op);refs.add(new Object[]{bytes.size(),label,false});emit(0,0);}
        byte[] finish(){byte[] result=bytes.toByteArray();for(Object[] ref:refs){int at=(int)ref[0],target=Objects.requireNonNull(labels.get(ref[1]));
            if((boolean)ref[2]){int offset=target-at-1;if(offset< -128||offset>127)throw new AssertionError("ROM branch range");result[at]=(byte)offset;}
            else {int address=0x8000+target;result[at]=(byte)address;result[at+1]=(byte)(address>>>8);}}
            return result;}
    }

    private record Crc(long frame,String value,int server,int mode) {}
    private static List<Crc> parse(String text) {
        var matcher=CRC.matcher(text);var values=new ArrayList<Crc>();
        while(matcher.find())values.add(new Crc(Long.parseLong(matcher.group(1)),matcher.group(2),Integer.parseInt(matcher.group(3)),Integer.parseInt(matcher.group(4))));
        return values;
    }
    private static void selfTest()throws Exception {
        String complete="[INFO] [PIQ POC CRC] frame=120 crc=1234abcd server=0 mode=10\n";
        check(parse(complete).equals(List.of(new Crc(120,"1234abcd",0,10))),"complete CRC event parsed");
        check(parse(complete.replace("\n","\r\n")).size()==1,"CRLF event parsed");
        for(int i=0;i<complete.length();i++)check(parse(complete.substring(0,i)).isEmpty(),"incomplete CRC prefix rejected "+i);
        check(parse(complete.replace("1234abcd","1234abc")).isEmpty(),"short CRC rejected");
        check(parse(complete.replace("1234abcd","1234abcde")).isEmpty(),"long CRC rejected");
        check(parse(complete.replace(" mode=10","")).isEmpty(),"missing role rejected");
        check(parse("partial-prefix "+complete).isEmpty(),"truncated first log line not promoted to event");
        check(ROM.length==24592&&ROM[6]==2&&ROM[8]==1,"original battery NROM geometry");
        check(Arrays.equals(ROM,rom()),"original ROM assembly deterministic");
        check((ROM[16+0x3ffc]&255)==0&&(ROM[16+0x3ffd]&255)==128,"original reset vector");
        PnpStreamTests.selfTest();
        phaseSelfTest();
    }

    /** A SYNC changes counters and SRAM before the first complete state is received.
     * Only that initial window can have a separate, reproducible cold-state identity.
     * A same-seed frame needs positive observation-before-delivery evidence.
     */
    private static boolean initialWindow(long frame,long observed,PnpStream.Command firstLoad) {
        return frame<firstLoad.frame()||frame==firstLoad.frame()&&observed<firstLoad.completeNanos();
    }
    private static boolean comparePhase(Crc host,Crc remote,long observed,PnpStream.Command firstLoad) {
        boolean initial=initialWindow(remote.frame,observed,firstLoad);
        if(!initial&&!host.value.equals(remote.value))throw new AssertionError("Strict post-restore CRC mismatch frame="+remote.frame+" host="+host.value+" remote="+remote.value);
        return initial;
    }
    private static Crc pairedHost(Map<Long,Crc> events,long frame) {
        Crc host=events.get(frame);if(host==null)throw new AssertionError("Unpaired remote CRC after closing at frame="+frame);
        return host;
    }
    private static long segmentMatches(Collection<Crc> samples,Map<Long,Crc> host,Map<Long,Long> observed,PnpStream.Command load,long next) {
        return samples.stream().filter(c->c.frame>load.frame()&&c.frame<next&&observed.get(c.frame)>=load.completeNanos())
                .filter(c->{var h=host.get(c.frame);return h!=null&&h.value.equals(c.value);}).count();
    }
    private static boolean restoreError(String text) {
        String lower=text.toLowerCase(Locale.ROOT);
        return lower.contains("netplay savestate loading failed")||lower.contains("netplay state load out of order")
                ||lower.contains("unexpected save state size")||lower.contains("invalid payload size for netplay_cmd_load_savestate")
                ||lower.contains("netplay_cmd_load_savestate from client")||lower.contains("netplay state load from a spectator")
                ||lower.contains("failed to initialize netplay");
    }
    private static void validateStateCommand(PnpStream.Command command) throws IOException {
        if(command.cmd()==0x01)throw new IOException("Native PNP7 NAK rejects the protocol operation");
        if(command.cmd()==0x42&&(command.core()==null||command.core().length!=FC_STATE_BYTES))
            throw new IOException("Fixed FCEUmm seed must contain exactly "+FC_STATE_BYTES+" core-state bytes");
    }
    private static void transportHealthy(AtomicReference<Throwable> failure) {
        if(failure.get()!=null)throw new AssertionError("Probe transport or protocol observation failed",failure.get());
    }
    private static void phaseSelfTest()throws Exception {
        var seed=new PnpStream.Command(0x42,60,59,100,null,new byte[]{1},1);
        check(initialWindow(59,110,seed),"pre-seed sample remains explicit initialization evidence");
        check(initialWindow(60,99,seed),"seed-boundary initialization needs positive pre-delivery timestamp");
        check(!initialWindow(60,100,seed)&&!initialWindow(60,101,seed),"seed-boundary state after delivery never gets cold-state exemption");
        check(!initialWindow(61,99,seed)&&!initialWindow(120,110,seed),"post-load CRC mismatch never gets cold-state exemption");
        var reopen=new PnpStream.Command(0x42,0,-1,100,null,new byte[]{1},1);
        check(!initialWindow(60,110,reopen),"historical Mesen or Nestopia post-reopen frame60 mismatch remains a failure");
        for(long[] sample:new long[][]{{60,100},{60,101},{61,110},{120,110}}){
            boolean rejected=false;try{comparePhase(new Crc(sample[0],"12345678",1,10),new Crc(sample[0],"87654321",0,8),sample[1],seed);}catch(AssertionError expected){rejected=true;}
            check(rejected,"strict mismatch rejected without a pre-delivery proof at frame="+sample[0]+" time="+sample[1]);
        }
        var corrected=new Crc(180,"12345678",0,10);
        check(segmentMatches(List.of(corrected),Map.of(180L,new Crc(180,"12345678",1,10)),Map.of(180L,300L),seed,120)==0,
                "a second LOAD correcting a failed/no-op first restore cannot supply the first segment's missing CRC");
        boolean unpaired=false;try{pairedHost(Map.of(),60);}catch(AssertionError expected){unpaired=true;}
        check(unpaired,"unpaired remote CRC after close is a failure");
        var lateRejection=new AtomicReference<Throwable>(new IOException("late NAK after last pump"));
        boolean lateFailed=false;try{transportHealthy(lateRejection);}catch(AssertionError expected){lateFailed=true;}
        check(lateFailed,"late protocol rejection remains a failure after transport termination");
        for(var invalid:List.of(new PnpStream.Command(1,-1,-1,100,null,null,1),
                new PnpStream.Command(0x42,60,59,100,null,new byte[0],1),new PnpStream.Command(0x42,60,59,100,null,new byte[FC_STATE_BYTES-1],1))){
            boolean rejected=false;try{validateStateCommand(invalid);}catch(IOException expected){rejected=true;}
            check(rejected,"NAK or empty/wrong-size fixed-core state cannot certify a seed");
        }
        for(String message:List.of("Netplay savestate loading failed: Prepare for desync!","Netplay state load out of order!",
                "Netplay state load with an unexpected save state size.","Received invalid payload size for NETPLAY_CMD_LOAD_SAVESTATE.",
                "NETPLAY_CMD_LOAD_SAVESTATE from client.","Netplay state load from a spectator.","Failed to initialize netplay."))
            check(restoreError(message),"native failed restore rejected: "+message);
    }


    /** Strict, incremental PNP7 observer. It makes no CRC or phase-acceptance decision. */
    static final class PnpStream {
        static final int MAX_PAYLOAD = 16 * 1024 * 1024;
        static final int SYNC_BYTES = 184 + 8192;
        record Command(int cmd, long frame, long priorInputMax, long completeNanos,
                       byte[] sram, byte[] core, long endOffset) {
            Command {
                sram = sram == null ? null : sram.clone();
                core = core == null ? null : core.clone();
            }
            @Override public byte[] sram() { return sram == null ? null : sram.clone(); }
            @Override public byte[] core() { return core == null ? null : core.clone(); }
        }

        private final byte[] header = new byte[24], commandHeader = new byte[8];
        private final java.util.ArrayList<Command> commands = new java.util.ArrayList<>();
        private int headerBytes, commandHeaderBytes, payloadBytes, command;
        private byte[] payload;
        private long nextSequence, offset, inputMax = -1;
        private boolean compressed, seenSync, finished;
        private java.io.IOException failure;

        java.util.List<Command> commands() { return java.util.List.copyOf(commands); }

        void feed(long sequence, byte[] bytes, long completedNanos) throws java.io.IOException {
            if (failure != null) throw new java.io.IOException("PNP7 parser previously failed", failure);
            try {
                if (finished) throw bad("feed after finish");
                if (sequence != nextSequence) throw bad("sequence " + sequence + ", expected " + nextSequence);
                if (bytes == null) throw bad("null transport chunk");
                int pos = 0;
                while (pos < bytes.length) {
                    if (headerBytes < header.length) {
                        int n = Math.min(header.length - headerBytes, bytes.length - pos);
                        System.arraycopy(bytes, pos, header, headerBytes, n);
                        headerBytes += n; pos += n; offset += n;
                        if (headerBytes == header.length) {
                            if (u32be(header, 0) != 0x52414e50L) throw bad("handshake magic is not RANP");
                            if (u32be(header, 16) != 7) throw bad("handshake protocol is not 7");
                            compressed = (u32be(header, 8) & 1) != 0;
                        }
                        continue;
                    }
                    if (payload == null) {
                        int n = Math.min(commandHeader.length - commandHeaderBytes, bytes.length - pos);
                        System.arraycopy(bytes, pos, commandHeader, commandHeaderBytes, n);
                        commandHeaderBytes += n; pos += n; offset += n;
                        if (commandHeaderBytes < commandHeader.length) continue;
                        long cmd = u32be(commandHeader, 0), size = u32be(commandHeader, 4);
                        if (cmd > Integer.MAX_VALUE) throw bad("invalid command id " + cmd);
                        if (size > MAX_PAYLOAD) throw bad("payload length exceeds 16 MiB: " + size);
                        command = (int) cmd; payload = new byte[(int) size]; payloadBytes = 0;
                        if (payload.length == 0) complete(completedNanos);
                        continue;
                    }
                    int n = Math.min(payload.length - payloadBytes, bytes.length - pos);
                    System.arraycopy(bytes, pos, payload, payloadBytes, n);
                    payloadBytes += n; pos += n; offset += n;
                    if (payloadBytes == payload.length) complete(completedNanos);
                }
                nextSequence++;
            } catch (java.io.IOException rejected) {
                failure = rejected;
                throw rejected;
            }
        }

        void finish() throws java.io.IOException {
            if (failure != null) throw new java.io.IOException("PNP7 parser previously failed", failure);
            if (finished) return;
            if (headerBytes != header.length || commandHeaderBytes != 0 || payload != null) {
                failure = bad("truncated handshake, command header or payload");
                throw failure;
            }
            finished = true;
        }

        private void complete(long completedNanos) throws java.io.IOException {
            long frame = -1, priorInputMax = inputMax;
            byte[] sram = null, core = null;
            switch (command) {
                case 3 -> {
                    if (payload.length < 8 || (payload.length & 3) != 0)
                        throw bad("INPUT requires frame/client index and aligned input words");
                    frame = u32be(payload, 0);
                    // Only server/client-zero input advances server_frame_count.
                    if ((u32be(payload, 4) & 0xffff) == 0) inputMax = Math.max(inputMax, frame);
                }
                case 4 -> {
                    if (payload.length != 4) throw bad("NOINPUT must contain exactly one frame");
                    frame = u32be(payload, 0);
                    inputMax = Math.max(inputMax, frame);
                }
                case 0x23 -> {
                    if (payload.length != SYNC_BYTES) throw bad("SYNC length must be " + SYNC_BYTES);
                    frame = u32be(payload, 0);
                    sram = java.util.Arrays.copyOfRange(payload, 184, payload.length);
                    seenSync = true;
                }
                case 0x42 -> {
                    if (!seenSync) throw bad("LOAD before SYNC");
                    if (payload.length < 8) throw bad("LOAD header is truncated");
                    frame = u32be(payload, 0);
                    long rawSize = u32be(payload, 4);
                    if (rawSize < 8 || rawSize > MAX_PAYLOAD) throw bad("invalid LOAD raw length " + rawSize);
                    byte[] raw = decodeLoad((int) rawSize);
                    core = coreBlock(raw);
                }
                case 0x46 -> {
                    if (payload.length != 4) throw bad("RESET must contain exactly one frame");
                    frame = u32be(payload, 0);
                }
                default -> { /* Retain the command without imposing unknown payload semantics. */ }
            }
            commands.add(new Command(command, frame, priorInputMax, completedNanos, sram, core, offset));
            payload = null; payloadBytes = 0; commandHeaderBytes = 0;
        }

        private byte[] decodeLoad(int rawSize) throws java.io.IOException {
            if (!compressed) {
                if (payload.length - 8 != rawSize) throw bad("raw LOAD size mismatch");
                return java.util.Arrays.copyOfRange(payload, 8, payload.length);
            }
            var inflater = new java.util.zip.Inflater();
            try {
                inflater.setInput(payload, 8, payload.length - 8);
                byte[] raw = new byte[rawSize], extra = new byte[1];
                int written = 0;
                while (!inflater.finished()) {
                    int n = written < raw.length
                            ? inflater.inflate(raw, written, raw.length - written)
                            : inflater.inflate(extra);
                    if (written == raw.length && n != 0) throw bad("inflated LOAD exceeds declared size");
                    written += n;
                    if (inflater.finished()) break;
                    if (inflater.needsDictionary()) throw bad("LOAD zlib requires a dictionary");
                    if (inflater.needsInput()) throw bad("truncated LOAD zlib stream");
                    if (n == 0) throw bad("LOAD zlib made no progress");
                }
                if (written != rawSize) throw bad("inflated LOAD size mismatch");
                if (inflater.getRemaining() != 0) throw bad("trailing bytes after LOAD zlib stream");
                return raw;
            } catch (java.util.zip.DataFormatException invalid) {
                throw new java.io.IOException("PNP7 invalid LOAD zlib at byte " + offset, invalid);
            } finally {
                inflater.end();
            }
        }

        private byte[] coreBlock(byte[] raw) throws java.io.IOException {
            byte[] magic = {'N', 'E', 'T', 'P', 'L', 'A', 'Y', 1};
            if (raw.length < magic.length || !java.util.Arrays.equals(raw, 0, 8, magic, 0, 8))
                throw bad("LOAD is not NETPLAY version 1");
            int pos = 8;
            byte[] core = null;
            while (pos < raw.length) {
                if (raw.length - pos < 8) throw bad("truncated LOAD block header");
                for (int i = 0; i < 4; i++)
                    if ((raw[pos + i] & 255) < 32 || (raw[pos + i] & 255) > 126)
                        throw bad("non-ASCII LOAD block tag");
                String tag = new String(raw, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long length = u32le(raw, pos + 4), padded = (length + 7) & ~7L;
                long end = (long) pos + 8 + padded;
                if (end > raw.length) throw bad("truncated or oversized LOAD block " + tag);
                if (tag.equals("MEM ")) {
                    if (core != null) throw bad("duplicate MEM block");
                    if (length == 0) throw bad("empty MEM block");
                    core = java.util.Arrays.copyOfRange(raw, pos + 8, pos + 8 + (int) length);
                }
                if (tag.equals("END ")) {
                    if (length != 0 || end != raw.length) throw bad("END-space length zero must terminate LOAD exactly");
                    if (core == null) throw bad("LOAD has no MEM block");
                    return core;
                }
                pos = (int) end;
            }
            throw bad("LOAD has no END-space terminator");
        }

        private java.io.IOException bad(String reason) {
            return new java.io.IOException("PNP7 stream at byte " + offset + ": " + reason);
        }
        private static long u32be(byte[] b, int p) {
            return ((long)(b[p] & 255) << 24) | ((long)(b[p + 1] & 255) << 16)
                    | ((long)(b[p + 2] & 255) << 8) | (b[p + 3] & 255);
        }
        private static long u32le(byte[] b, int p) {
            return ((long)(b[p + 3] & 255) << 24) | ((long)(b[p + 2] & 255) << 16)
                    | ((long)(b[p + 1] & 255) << 8) | (b[p] & 255);
        }
    }

    /** Self-contained synthetic parser tests; no files, emulator or native code. */
    static final class PnpStreamTests {
    private static int checks;
    private static final byte[] CORE = {1, 9, 3, 7, 0, -1, 4, 8, 11};
    private static final byte[] SRAM = new byte[8192];
    private static final byte[] MAGIC = {'N','E','T','P','L','A','Y',1};
    static { for (int i=0;i<SRAM.length;i++) SRAM[i]=(byte)(i*31); }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static void rejects(String label, Operation action) throws Exception {
        checks++;
        try { action.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError("Expected java.io.IOException: "+label);
    }
    private static byte[] concat(byte[]... chunks) {
        var out=new java.io.ByteArrayOutputStream();
        for(byte[] b:chunks)out.writeBytes(b);
        return out.toByteArray();
    }
    private static byte[] be(int... values) {
        var b=java.nio.ByteBuffer.allocate(values.length*4);
        for(int n:values)b.putInt(n);
        return b.array();
    }
    private static byte[] header(boolean zlib) { return be(0x52414e50,0,zlib?1:0,0,7,0); }
    private static byte[] command(int id, byte[] payload) { return concat(be(id,payload.length),payload); }
    private static byte[] sync(int frame) {
        byte[] metadata=new byte[184];java.nio.ByteBuffer.wrap(metadata).putInt(frame);
        return command(0x23,concat(metadata,SRAM));
    }
    private static byte[] block(String tag, byte[] data) {
        int aligned=(data.length+7)&~7;
        return concat(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(data.length).array(),
                java.util.Arrays.copyOf(data,aligned));
    }
    private static byte[] raw(byte[]... blocks) { return concat(MAGIC,concat(blocks)); }
    private static byte[] state() { return raw(block("META",new byte[]{5}),block("MEM ",CORE),block("END ",new byte[0])); }
    private static byte[] zipped(byte[] raw) throws java.io.IOException {
        var out=new java.io.ByteArrayOutputStream();
        try(var zlib=new java.util.zip.DeflaterOutputStream(out)){zlib.write(raw);}
        return out.toByteArray();
    }
    private static byte[] load(int frame,byte[] raw,boolean zlib) throws java.io.IOException {
        return loadBytes(frame,raw.length,zlib?zipped(raw):raw);
    }
    private static byte[] loadBytes(int frame,int size,byte[] body) {return command(0x42,concat(be(frame,size),body));}
    private static PnpStream parse(byte[] bytes) throws java.io.IOException {
        var p=new PnpStream();p.feed(0,bytes,11);p.finish();return p;
    }
    private static byte[] valid(boolean zlib) throws java.io.IOException {
        return concat(header(zlib),command(0x99,new byte[0]),command(3,be(10,0,7)),
                command(3,be(999,1,8)),command(4,be(15)),sync(17),load(21,state(),zlib),
                command(0x46,be(22)),command(3,be(13,0,4)),load(24,state(),zlib));
    }
    private static void validAssertions(PnpStream p, int byteCount) {
        var commands=p.commands();check(commands.size()==9,"all commands retained");
        var sync=commands.get(4);var load=commands.get(5);var reset=commands.get(6);
        check(sync.cmd()==0x23&&sync.frame()==17&&sync.priorInputMax()==15,"SYNC frame and server input bound");
        check(java.util.Arrays.equals(sync.sram(),SRAM)&&sync.core()==null,"exact SYNC SRAM");
        check(load.cmd()==0x42&&load.frame()==21&&load.priorInputMax()==15,"LOAD prior server input bound excludes other client");
        check(java.util.Arrays.equals(load.core(),CORE)&&load.sram()==null,"exact unpadded MEM core");
        check(reset.cmd()==0x46&&reset.frame()==22,"RESET retained");
        check(commands.get(8).priorInputMax()==15,"input maximum never decreases");
        check(commands.get(8).endOffset()==byteCount,"absolute command end offset");
        check(commands.get(0).frame()==-1,"unknown command retained without invented frame");
    }
    private static void synthetic() throws Exception {
        for(boolean zlib:new boolean[]{false,true}) {
            byte[] bytes=valid(zlib);
            validAssertions(parse(bytes),bytes.length);
            // Every possible two-chunk split, including inside the handshake/length/zlib trailer.
            for(int split=0;split<=bytes.length;split++) {
                var p=new PnpStream();
                p.feed(0,java.util.Arrays.copyOfRange(bytes,0,split),100);
                p.feed(1,java.util.Arrays.copyOfRange(bytes,split,bytes.length),200);p.finish();
                validAssertions(p,bytes.length);
                for(var c:p.commands())check(c.completeNanos()==(c.endOffset()<=split?100:200),"chunk completion timestamp");
            }
            for(int seed=0;seed<40;seed++) {
                var random=new java.util.Random(seed);var p=new PnpStream();
                int pos=0;long seq=0;
                while(pos<bytes.length){int next=Math.min(bytes.length,pos+1+random.nextInt(37));p.feed(seq++,java.util.Arrays.copyOfRange(bytes,pos,next),seq);pos=next;}
                p.finish();validAssertions(p,bytes.length);
            }
            var one=new PnpStream();
            for(int i=0;i<bytes.length;i++)one.feed(i,new byte[]{bytes[i]},i+1);
            one.finish();validAssertions(one,bytes.length);
            for(var c:one.commands())check(c.completeNanos()==c.endOffset(),"single-byte timestamp");
        }
        for(int n=0;n<24;n++){final int end=n;rejects("truncated handshake "+n,()->parse(java.util.Arrays.copyOf(header(true),end)));}
        byte[] sync=sync(0);
        for(int n=1;n<sync.length;n++){final int end=n;rejects("truncated command "+n,()->parse(concat(header(true),java.util.Arrays.copyOf(sync,end))));}
        byte[] h=header(true);h[0]^=1;byte[] badMagic=h;rejects("magic",()->parse(badMagic));
        rejects("protocol",()->parse(be(0x52414e50,0,1,0,6,0)));
        for(int size:new int[]{-1,Integer.MIN_VALUE,PnpStream.MAX_PAYLOAD+1})
            rejects("invalid payload length "+size,()->parse(concat(header(true),be(0x99,size))));
        rejects("negative command",()->parse(concat(header(true),be(-1,0))));
        rejects("negative first seq",()->new PnpStream().feed(-1,new byte[0],0));
        rejects("skipped first seq",()->new PnpStream().feed(1,new byte[0],0));
        var sequenced=new PnpStream();sequenced.feed(0,header(false),1);
        rejects("duplicate seq",()->sequenced.feed(0,new byte[0],2));
        rejects("failed parser remains failed",()->sequenced.feed(1,new byte[0],3));
        rejects("failed finish remains failed",sequenced::finish);
        var gap=new PnpStream();gap.feed(0,header(false),1);rejects("seq gap",()->gap.feed(2,new byte[0],2));
        var done=parse(header(false));done.finish();rejects("feed after finish",()->done.feed(1,new byte[0],2));
        for(int n:new int[]{0,184,8375,8377})rejects("SYNC size "+n,()->parse(concat(header(false),command(0x23,new byte[n]))));
        rejects("INPUT short",()->parse(concat(header(false),command(3,be(0)))));
        rejects("INPUT unaligned",()->parse(concat(header(false),command(3,new byte[9]))));
        rejects("NOINPUT short",()->parse(concat(header(false),command(4,new byte[3]))));
        rejects("NOINPUT long",()->parse(concat(header(false),command(4,new byte[8]))));
        rejects("RESET short",()->parse(concat(header(false),command(0x46,new byte[0]))));
        rejects("RESET long",()->parse(concat(header(false),command(0x46,new byte[8]))));
        rejects("LOAD before SYNC",()->parse(concat(header(true),load(0,state(),true))));
        byte[] prefix=concat(header(true),sync(0));byte[] encoded=zipped(state());
        byte[] corrupt=encoded.clone();corrupt[corrupt.length-1]^=1;
        rejects("bad zlib checksum",()->parse(concat(prefix,loadBytes(0,state().length,corrupt))));
        rejects("truncated zlib",()->parse(concat(prefix,loadBytes(0,state().length,java.util.Arrays.copyOf(encoded,encoded.length-1)))));
        rejects("trailing zlib bytes",()->parse(concat(prefix,loadBytes(0,state().length,concat(encoded,new byte[]{0})))));
        rejects("concatenated zlib streams",()->parse(concat(prefix,loadBytes(0,state().length,concat(encoded,encoded)))));
        for(int size:new int[]{state().length-1,state().length+1,-1,Integer.MIN_VALUE,0,7,PnpStream.MAX_PAYLOAD+1})
            rejects("raw size "+size,()->parse(concat(prefix,loadBytes(0,size,encoded))));
        rejects("short LOAD header",()->parse(concat(prefix,command(0x42,new byte[7]))));
        rejects("raw size mismatch",()->parse(concat(header(false),sync(0),loadBytes(0,state().length+1,state()))));
        byte[] noEnd=raw(block("MEM ",CORE)),noMem=raw(block("END ",new byte[0]));
        byte[] duplicate=raw(block("MEM ",CORE),block("MEM ",CORE),block("END ",new byte[0]));
        byte[] afterEnd=raw(block("MEM ",CORE),block("END ",new byte[0]),block("NEXT",new byte[0]));
        byte[] badEnd=raw(block("MEM ",CORE),block("END ",new byte[]{1}));
        byte[] wrongEnd=raw(block("MEM ",CORE),block("END0",new byte[0]));
        byte[] emptyMem=raw(block("MEM ",new byte[0]),block("END ",new byte[0]));
        byte[] missingPad=concat(MAGIC,"MEM ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),new byte[]{9,0,0,0},CORE);
        byte[] badLength=concat(MAGIC,"MEM ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),new byte[]{-1,-1,-1,-1});
        byte[] badTag=state();badTag[8]=0;
        byte[] wrongVersion=state();wrongVersion[7]=2;
        for(byte[] raw:java.util.List.of(noEnd,noMem,duplicate,afterEnd,badEnd,wrongEnd,emptyMem,missingPad,badLength,badTag,wrongVersion,concat(MAGIC,new byte[4])))
            rejects("invalid NETPLAY block structure",()->parse(concat(prefix,load(0,raw,true))));
        var clientFlags=parse(concat(header(false),sync(0),command(3,be(100,0x10000,7)),
                command(3,be(999,0x10001,8)),load(101,state(),false)));
        check(clientFlags.commands().getLast().priorInputMax()==100,"native low16 client index keeps flagged server input and excludes flagged peer");
        // Valid unknown metadata and nonzero alignment padding are preserved as format, not interpreted.
        byte[] padded=state();padded[17]=91;parse(concat(prefix,load(0,padded,true)));checks++;
        var immutable=parse(valid(true));var commands=immutable.commands();
        try{commands.clear();throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException expected){checks++;}
        byte[] copy=commands.get(5).core();copy[0]=42;check(commands.get(5).core()[0]==CORE[0],"defensive core accessor");
        System.out.println("SYNTHETIC PASS checks="+checks);
    }

        static void selfTest() throws Exception { checks=0; synthetic(); }
    }

    private static final class DiskSave implements NetplayProcess.Persistence {
        final boolean resume;final AtomicInteger commits=new AtomicInteger(),finishes=new AtomicInteger();
        NetplaySaveStore store;NetplaySaveState.Identity identity;volatile byte[] latest;
        DiskSave(boolean resume){this.resume=resume;}
        public synchronized byte[] load(NetplaySaveState.Identity expected)throws Exception {
            identity=expected;
            check(expected.equals(NetplaySaveState.identity(PROFILE,NetplaySaveState.hash(ROM),Map.of())),"exact production Netplay save identity");
            store=new NetplaySaveStore(root.resolve("owned-save"),expected);latest=store.read();
            check(resume==(latest!=null),resume?"second generation loads durable checkpoint":"first generation has no old checkpoint");
            return latest;
        }
        public synchronized CompletableFuture<Void> save(byte[] bytes) {
            try {var p=NetplaySaveState.decode(bytes,identity);
                if(p.state().length==0||p.ram().length!=8192||(p.ram()[0]&255)!=0x5a)throw new AssertionError("Invalid original-ROM checkpoint");
                store.write(bytes);latest=bytes.clone();commits.incrementAndGet();return CompletableFuture.completedFuture(null);
            }catch(Exception failure){return CompletableFuture.failedFuture(failure);}
        }
        public synchronized CompletableFuture<Void> finish(){try{store.close();store=null;finishes.incrementAndGet();return CompletableFuture.completedFuture(null);}
            catch(Exception failure){return CompletableFuture.failedFuture(failure);}}
        public synchronized void abort(){try{if(store!=null){store.close();store=null;}}catch(Exception failure){throw new IllegalStateException(failure);}}
    }

    private static final class Room implements AutoCloseable {
        final int generation;final long id;final DiskSave saves;final Object authority=new Object();
        final Map<Object,Run> active=new ConcurrentHashMap<>();final List<Run> members=new ArrayList<>();
        final AtomicReference<Throwable> transportFailure=new AtomicReference<>();
        final ScheduledThreadPoolExecutor transport=new ScheduledThreadPoolExecutor(1);
        final NetplayRelay<Object> relay;
        Run host,player,observer;
        Room(int generation){this.generation=generation;id=2026101600L+generation;saves=new DiskSave(generation>0);
            transport.setRemoveOnCancelPolicy(true);
            relay=new NetplayRelay<>(id,HOST,(who,delivery)->{Run run=active.get(who);if(run!=null){
                try{run.observeProtocol(delivery.chunk());}catch(IOException failure){throw new UncheckedIOException(failure);}
                run.process.receive(delivery.chunk(),delivery.port());}},2);
            transport.scheduleAtFixedRate(()->{try{synchronized(authority){relay.renew(Set.copyOf(active.keySet()));}}catch(Throwable failure){transportFailure.compareAndSet(null,failure);}},0,25,TimeUnit.MILLISECONDS);
        }
        void send(Object who,NetplayChunk chunk){
            // All writes enter one FIFO scheduling lane. Constant P2 delay preserves stream order.
            if(transport.isShutdown())return;
            transport.schedule(()->{try{relay.receive(who,chunk);}catch(Throwable failure){transportFailure.compareAndSet(null,failure);}},
                    who==PLAYER?delayMillis:0,TimeUnit.MILLISECONDS);
        }
        Run open(Object who,int port,String name)throws Exception {
            Run run;NetplayProcess process;
            // Grant and publication form one authority update. Renewal must not revoke a
            // newly granted peer while its local adapter is still being constructed.
            synchronized(authority){
            var ticket=Objects.requireNonNull(relay.grant(who,port));
            process=new NetplayProcess(new NetplayProcess.Grant(id,ticket.id(),who==HOST,port>=0,port),()->ROM.clone(),c->send(who,c),PROFILE,Map::of);
            run=new Run(this,who,"g"+generation+"-"+name,process);members.add(run);allRuns.add(run);active.put(who,run);
            }
            if(who==HOST)process.persistence(saves);
            else {boolean refused=false;try{process.persistence(new DiskSave(false));}catch(IllegalStateException expected){refused=true;}
                check(refused,name+" persistence binding denied before start");}
            process.start();until(name+" local AV ready",()->process.ready()&&run.lastFrame>20&&run.picture!=null);
            run.observeChild();run.snapshot("startup");
            check(!process.nativeSlotHeld(),name+" uses independent RetroArch, no JNI slot");
            check(process.role()==(who==HOST?NetplayProcess.Role.HOST:who==PLAYER?NetplayProcess.Role.PARTICIPANT:NetplayProcess.Role.SPECTATOR),name+" production role");
            if(who!=HOST)until(name+" every current state segment has two post-restore CRC matches",()->active.values().stream().filter(r->r.who!=HOST).allMatch(r->r.currentSegmentMatches()>=2));
            return run;
        }
        void pump()throws Exception {
            transportHealthy(transportFailure);
            for(Run run:List.copyOf(active.values())){run.observeChildIfAvailable();run.poll();}
            if(!relay.lastRejection().isEmpty())throw new AssertionError("Relay rejected: "+relay.lastRejection());
            for(Run run:members)if(run.who!=HOST)for(var entry:run.crcs.entrySet()) {
                Crc h=host==null?null:host.crcs.get(entry.getKey());
                if(h!=null&&!h.value.equals(entry.getValue().value)) {
                    var loads=run.loads();
                    // A not-yet-complete first LOAD is pending evidence, never a pass.
                    // Closing requires a complete stream, all state segments, and an exact
                    // cold-state CRC reproduction for every initial difference.
                    if(loads.isEmpty())continue;
                    comparePhase(h,entry.getValue(),run.crcObserved.get(entry.getKey()),loads.getFirst());
                }
            }
        }
        void until(String label,BooleanSupplier condition)throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
            while(System.nanoTime()<deadline){pump();if(condition.getAsBoolean()){check(true,label);return;}Thread.sleep(10);}
            throw new AssertionError("Timeout: "+label);
        }
        void drain(long millis)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<end){pump();Thread.sleep(10);}}
        void assertPads(int p1,int p2,String label)throws Exception {
            host.process.inputRetroPad(p1==0?0:256);player.process.inputRetroPad(p2==0?0:256);
            drain(800);host.process.saveNow().get(20,TimeUnit.SECONDS);pump();
            var parts=NetplaySaveState.decode(saves.latest,saves.identity);
            check((parts.ram()[1]&255)==p1&&(parts.ram()[2]&255)==p2,label+" real independent pad bytes in SRAM");
            String expected=host.picture;
            until(label+" three local pictures agree",()->expected.equals(host.picture)&&expected.equals(player.picture)&&expected.equals(observer.picture));
        }
        void rejectSave(Run run)throws Exception {
            check(!run.process.canSave(),run.name+" cannot save");
            denied(run.process.checkpoint(),run.name+" checkpoint denied");denied(run.process.saveNow(),run.name+" saveNow denied");
        }
        void verifyCrc(int minimum)throws Exception {
            pump();
            for(Run run:members)if(run.who!=HOST){long count=run.crcs.keySet().stream().filter(host.crcs::containsKey).count();
                check(count>=minimum,run.name+" strict same-frame CRC count="+count);
                int expected=run.who==PLAYER?10:8;
                check(run.crcs.values().stream().anyMatch(c->c.mode==expected&&c.server==0),run.name+" native role="+expected+" observed, not host-authority imitation");
            }
        }
        void verifyProtocolAfterClose(int minimum)throws Exception {
            transportHealthy(transportFailure);
            for(Run run:members)if(run.who!=HOST)run.verifyProtocol(minimum);
        }
        public void close()throws Exception {
            // Request every close even if an earlier await fails; never delete an unconfirmed workspace.
            Throwable failure=null;
            for(Run run:members)if(run.who!=HOST){
                try{run.observeChildIfAvailable();}catch(Throwable t){if(failure==null)failure=t;else failure.addSuppressed(t);}
                try{run.requestClose();}catch(Throwable t){if(failure==null)failure=t;else failure.addSuppressed(t);}
            }
            for(Run run:members)if(run.who!=HOST)try{run.close();}catch(Throwable t){if(failure==null)failure=t;else failure.addSuppressed(t);}
            // A failed HOST open may throw before the caller assigns the host field.
            for(Run run:members)if(run.who==HOST)try{run.close();}catch(Throwable t){if(failure==null)failure=t;else failure.addSuppressed(t);}
            relay.close();transport.shutdown();if(!transport.awaitTermination(5,TimeUnit.SECONDS)){transport.shutdownNow();if(failure==null)failure=new AssertionError("Probe transport not reclaimed");}
            try{transportHealthy(transportFailure);}catch(Throwable t){if(failure==null)failure=t;else failure.addSuppressed(t);}
            if(failure instanceof Exception e)throw e;if(failure!=null)throw new AssertionError("Room cleanup failed",failure);
        }
    }

    private static void denied(CompletableFuture<?> future,String label)throws Exception {
        try{future.get(2,TimeUnit.SECONDS);throw new AssertionError(label);}
        catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException,label);}
    }
    private static final class Run implements AutoCloseable {
        final Room room;final Object who;final String name;final NetplayProcess process;
        final Map<Long,Crc> crcs=new TreeMap<>();long lastFrame,audioSamples,nonzeroSamples;String picture;
        final Map<Long,Long> crcObserved=new TreeMap<>();
        final PnpStream protocol=new PnpStream();final List<PnpStream.Command> stateCommands=new ArrayList<>();
        int observedCommands;long streamBytes;
        Path workspace;ProcessHandle child;long childPid;Instant childStarted;boolean closed;volatile boolean closing;
        Run(Room room,Object who,String name,NetplayProcess process){this.room=room;this.who=who;this.name=name;this.process=process;}
        void observeProtocol(NetplayChunk chunk)throws IOException {
            if(who!=HOST&&chunk.kind()==NetplayChunk.CLOSE&&!closing)throw new IOException(name+" unexpected relay close before requested shutdown");
            if(who==HOST||chunk.kind()!=NetplayChunk.DATA)return;
            synchronized(protocol){
                byte[] bytes=chunk.bytes();long observed=System.nanoTime();
                Files.write(root.resolve(name+"-native-stream.bin"),bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                append(root.resolve(name+"-stream-chunks.tsv"),chunk.sequence()+"\t"+streamBytes+"\t"+bytes.length+"\t"+observed+"\n");
                protocol.feed(chunk.sequence(),bytes,observed);streamBytes+=bytes.length;
                var commands=protocol.commands();
                for(int i=observedCommands;i<commands.size();i++){
                    var command=commands.get(i);
                    validateStateCommand(command);
                    if(command.cmd()==0x02&&!closing)throw new IOException(name+" native DISCONNECT before requested shutdown");
                    if(command.cmd()==0x23||command.cmd()==0x42||command.cmd()==0x46){
                        stateCommands.add(command);String prefix=name+"-command-"+stateCommands.size();
                        append(root.resolve(name+"-state-commands.tsv"),command.cmd()+"\t"+command.frame()+"\t"+command.priorInputMax()+"\t"+command.completeNanos()+"\t"+command.endOffset()+"\n");
                        if(command.sram()!=null)Files.write(root.resolve(prefix+"-sync-sram.bin"),command.sram(),StandardOpenOption.CREATE_NEW);
                        if(command.core()!=null)Files.write(root.resolve(prefix+"-core.bin"),command.core(),StandardOpenOption.CREATE_NEW);
                    }
                }
                observedCommands=commands.size();
            }
        }
        List<PnpStream.Command> states(){synchronized(protocol){return List.copyOf(stateCommands);}}
        List<PnpStream.Command> loads(){return states().stream().filter(c->c.cmd()==0x42).toList();}
        long currentSegmentMatches() {
            var loads=loads();if(loads.isEmpty()||room.host==null)return 0;
            var load=loads.getLast();if(load.priorInputMax()>=load.frame())return 0;
            return segmentMatches(crcs.values(),room.host.crcs,crcObserved,load,Long.MAX_VALUE);
        }
        void verifyProtocol(int minimum)throws Exception {
            synchronized(protocol){protocol.finish();}
            var states=states();var syncs=states.stream().filter(c->c.cmd()==0x23).toList();var loads=loads();
            check(syncs.size()==1&&!loads.isEmpty()&&states.stream().noneMatch(c->c.cmd()==0x46),name+" complete single-SYNC stream without an untested RESET");
            var first=loads.getFirst();check(syncs.getFirst().frame()<=first.frame(),name+" first state follows SYNC frame");
            int initialDifferences=0,postCompared=0;var initialTargets=new LinkedHashMap<String,List<Long>>();
            for(var c:crcs.values()){
                Crc host=pairedHost(room.host.crcs,c.frame);
                boolean initial=comparePhase(host,c,crcObserved.get(c.frame),first);boolean equal=host.value.equals(c.value);
                append(root.resolve(name+"-crc-phases.tsv"),c.frame+"\t"+c.value+"\t"+host.value+"\t"+crcObserved.get(c.frame)+"\t"+(initial?"INITIALIZATION":"STATE")+"\t"+equal+"\n");
                if(initial){if(!equal){initialDifferences++;initialTargets.computeIfAbsent(c.value,k->new ArrayList<>()).add(c.frame);}}
                else {check(equal,name+" strict complete state CRC frame="+c.frame);postCompared++;}
            }
            for(int i=0;i<loads.size();i++){
                var load=loads.get(i);long next=i+1<loads.size()?loads.get(i+1).frame():Long.MAX_VALUE;
                check(load.priorInputMax()<load.frame()&&load.frame()<next,name+" ordered state segment "+i+" has a strict server-input bound");
                long count=segmentMatches(crcs.values(),room.host.crcs,crcObserved,load,next);
                check(count>=minimum,name+" state segment "+i+" post-restore CRC count="+count);
                append(root.resolve(name+"-state-segments.tsv"),i+"\t"+load.frame()+"\t"+next+"\t"+load.priorInputMax()+"\t"+load.completeNanos()+"\t"+count+"\t"+NetplaySaveState.hash(load.core())+"\n");
            }
            if(!initialTargets.isEmpty())explainInitialStates(name,syncs.getFirst().sram(),initialTargets);
            int expected=who==PLAYER?10:8;
            append(root.resolve("crc-summary.tsv"),name+"\t"+postCompared+"\t0\t"+expected+"\t"+initialDifferences+"\t"+loads.size()+"\n");
        }
        void poll()throws Exception {
            capture();checkConnection();if(process.error()!=null)throw new AssertionError(name+": "+process.diagnostic());
            NetplayProcess.Frame f;while((f=process.poll())!=null){
                if(f.rgba().length!=f.width()*f.height()*4||f.sampleRate()!=48000||f.rotation()!=0)throw new AssertionError("Invalid FC AV metadata");
                lastFrame=f.number();picture=NetplaySaveState.hash(f.rgba());audioSamples+=f.stereo().length;
                for(short sample:f.stereo())if(sample!=0)nonzeroSamples++;
            }
        }
        void capture()throws Exception {
            String snapshot=process.diagnostic();
            for(Crc event:parse(snapshot)) {
                Crc old=crcs.putIfAbsent(event.frame,event);
                if(old!=null&&!old.equals(event)){snapshot("contradictory-crc");throw new AssertionError(name+" contradictory complete CRC event "+event.frame);}
                if(old==null){crcObserved.put(event.frame,System.nanoTime());append(root.resolve(name+"-crc.tsv"),event.frame+"\t"+event.value+"\t"+event.server+"\t"+event.mode+"\n");}
            }
            if(snapshot.contains("CRCs mismatch")||snapshot.contains("Netplay CRC mismatch"))throw new AssertionError(name+" native CRC mismatch warning");
            if(restoreError(snapshot))throw new AssertionError(name+" native state restore failure");
        }
        void snapshot(String phase)throws IOException {writeNew(root.resolve(name+"-"+phase+".log"),process.diagnostic());}
        void checkConnection() {
            if(who!=HOST&&!closing&&process.diagnostic().contains("[Netplay] Netplay disconnected"))throw new AssertionError(name+" disconnected before requested shutdown");
        }
        void requestClose()throws Exception {
            if(closing)return;
            // Inspect before setting the expected-close flag, so a prior unexpected
            // disconnect cannot be reclassified as part of this normal teardown.
            try{capture();checkConnection();}finally{closing=true;process.close();}
        }
        void observeChild()throws Exception {
            observeChildIfAvailable();check(workspace!=null,name+" native identity observed before AV readiness");
        }
        void observeChildIfAvailable()throws Exception {
            if(workspace!=null)return;
            Path sessions=root.resolve("instance/game-console/runtime-sessions");
            if(!Files.isDirectory(sessions,LinkOption.NOFOLLOW_LINKS))return;
            Path candidate;
            try(var paths=Files.list(sessions)){var candidates=paths.filter(p->p.getFileName().toString().startsWith("netplay-")&&!knownWorkspaces.contains(p)).toList();
                if(candidates.isEmpty())return;
                if(candidates.size()!=1)throw new AssertionError(name+" ambiguous new native workspaces");candidate=candidates.getFirst();}
            Path marker=candidate.resolve("child");
            if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS))return;
            String identityText=Files.readString(marker);
            // RuntimeWorkspace writes this marker after OS creation, before starting log reads.
            // Its trailing newline distinguishes a complete identity from a concurrent short write.
            if(!identityText.endsWith("\n"))return;
            var identity=identityText.lines().toList();var owner=Files.readAllLines(candidate.resolve("owner"));
            var parent=ProcessHandle.current();
            check(Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS)&&owner.size()==4
                    &&owner.get(0).equals("PIQ-RUNTIME-WORKSPACE-1")&&owner.get(1).equals(candidate.getFileName().toString())
                    &&owner.get(2).equals(Long.toString(parent.pid()))&&owner.get(3).equals(parent.info().startInstant().orElseThrow().toString()),
                    name+" workspace belongs to this exact probe process");
            check(identity.size()==2&&!identity.get(1).equals("unknown"),name+" native start identity available");
            childPid=Long.parseLong(identity.get(0));childStarted=Instant.parse(identity.get(1));
            workspace=candidate;knownWorkspaces.add(workspace);
            append(root.resolve("children.tsv"),name+"\t"+childPid+"\t"+childStarted+"\t"+workspace.getFileName()+"\n");
            child=ProcessHandle.of(childPid).orElse(null);
            check(child!=null&&child.isAlive()&&child.info().startInstant().orElseThrow().equals(childStarted),name+" exact native process alive");
            check(Path.of(child.info().command().orElseThrow()).toRealPath().equals(workspace.resolve("retroarch.exe").toRealPath()),name+" child executable belongs to its owned workspace");
            check(sha(workspace.resolve("retroarch.exe")).equals(RUNNER_SHA),name+" extracted runner matches immutable pin");
            check(sha(workspace.resolve("core.dll")).equals(CORE_SHA),name+" extracted unmodified FCEUmm matches immutable pin");
            check(Arrays.equals(ROM,Files.readAllBytes(workspace.resolve("content.nes"))),name+" original diagnostic content staged unchanged");
        }
        public void close()throws Exception {
            if(closed)return;
            Throwable evidenceFailure=null;try{observeChildIfAvailable();}catch(Throwable failure){evidenceFailure=failure;}
            try{requestClose();}catch(Throwable failure){if(evidenceFailure==null)evidenceFailure=failure;else evidenceFailure.addSuppressed(failure);}
            process.terminated().get(45,TimeUnit.SECONDS);capture();
            room.active.remove(who,this);closed=true;snapshot("closed");
            check(!process.ready(),name+" readiness cleared after termination");
            if(childPid!=0)check(ProcessHandle.of(childPid).map(p->!p.isAlive()||!p.info().startInstant().orElse(Instant.MIN).equals(childStarted)).orElse(true),name+" exact child exited");
            if(workspace!=null)check(Files.notExists(workspace,LinkOption.NOFOLLOW_LINKS),name+" workspace reclaimed after exit");
            check(!process.forcedTermination(),name+" no forced native termination");
            check(process.diagnostic().contains("Child exit=0; forced=false"),name+" natural native exit code zero");
            if(evidenceFailure instanceof Exception failure)throw failure;
            if(evidenceFailure!=null)throw new AssertionError("Native identity capture failed",evidenceFailure);
        }
    }

    private static void validatePackagedInputs()throws Exception {
        var source=NetplayProcess.class.getProtectionDomain().getCodeSource().getLocation();
        productionJar=Path.of(source.toURI()).toRealPath();
        check(Files.isRegularFile(productionJar)&&productionJar.toString().endsWith(".jar"),"production classes loaded from one JAR");
        for(Class<?> type:List.of(FcRetroarchPad.class,NetplaySaveState.class,LibretroJniRuntime.class,NativeLibretroBridge.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(productionJar),
                    "production declaration/runtime not shadowed: "+type.getSimpleName());
        originalJarSha=sha(productionJar);
        for(String resource:List.of("/core/netplay/piq-retroarch.exe",PROFILE.resource())) {
            var url=Objects.requireNonNull(NetplayProcess.class.getResource(resource));
            check(url.openConnection() instanceof JarURLConnection,"native resource originates in JAR: "+resource);
            var connection=(JarURLConnection)url.openConnection();
            check(Path.of(connection.getJarFileURL().toURI()).toRealPath().equals(productionJar),"native resource not shadowed: "+resource);
            try(var in=url.openStream()){check(NetplaySaveState.hash(in.readAllBytes()).equals(resource.endsWith(".exe")?RUNNER_SHA:CORE_SHA),"packaged pin: "+resource);}
        }
        check(PROFILE.owner()==NetplayProcess.class&&PROFILE.jni()==null&&PROFILE.sha().equals(CORE_SHA)
                &&PROFILE.resource().equals("/core/fc-retroarch/windows-x64/fceumm_libretro.dll")
                &&PROFILE.device()==257&&PROFILE.ports()==2&&PROFILE.sampleRate()==48000,
                "final packaged FC RetroArch pad profile, not legacy Mesen or JNI r2");
        writeNew(root.resolve("inputs.txt"),"productionJarSha256="+originalJarSha+"\nrunnerSha256="+RUNNER_SHA+"\ncoreSha256="+CORE_SHA+"\noriginalRomSha256="+NetplaySaveState.hash(ROM)+"\np2OneWayDelayMillis="+delayMillis+"\nprofile="+PROFILE+"\n");
    }
    private static List<LibretroProcess.Controls> controls(int a,int b) {
        return List.of(new LibretroProcess.Controls(new int[]{a,b},0));
    }
    private static int restoreComparisons,restoreDifferences,restoreOpens;
    private record Sample(byte[] state,byte[] ram,byte[] rtc,byte[] video,byte[] audio) {}
    private static boolean compareState(Path output,String stage,byte[] expected,byte[] actual)throws Exception {
        restoreComparisons++;boolean equal=Arrays.equals(expected,actual);
        int different=0;var offsets=new StringBuilder();
        for(int i=0;i<Math.max(expected.length,actual.length);i++)if(i>=expected.length||i>=actual.length||expected[i]!=actual[i]) {
            offsets.append(i).append(':').append(i<expected.length?expected[i]&255:-1).append("->")
                    .append(i<actual.length?actual[i]&255:-1).append(' ');
            different++;
        }
        append(output.resolve("comparisons.tsv"),stage+"\t"+equal+"\t"+expected.length+"\t"+actual.length+"\t"+different
                +"\t"+NetplaySaveState.hash(expected)+"\t"+NetplaySaveState.hash(actual)+"\n");
        if(!equal) {
            restoreDifferences++;Files.write(output.resolve(stage+"-expected.bin"),expected,StandardOpenOption.CREATE_NEW);
            Files.write(output.resolve(stage+"-actual.bin"),actual,StandardOpenOption.CREATE_NEW);
            writeNew(output.resolve(stage+"-offsets.txt"),offsets+"\n");
            System.out.println("DIFFERENT "+output.getFileName()+"/"+stage+" bytes="+different);
        }
        return equal;
    }
    private static Sample capture(LibretroJniRuntime core,LibretroProcess.Output output) {
        var pcm=ByteBuffer.allocate(output.stereo().length*2).order(ByteOrder.LITTLE_ENDIAN);
        for(short value:output.stereo())pcm.putShort(value);
        return new Sample(core.serialize(),core.memory(0),core.memory(1),output.rgba(),pcm.array());
    }
    private static List<Sample> replay(LibretroJniRuntime core) {
        var samples=new ArrayList<Sample>();
        for(int frame=0;frame<120;frame++)samples.add(capture(core,core.run(controls((frame%2)*256,((frame/2)%2)*256),3)));
        return samples;
    }
    private static boolean compareReplay(Path output,List<Sample> expected,List<Sample> actual)throws Exception {
        Files.createDirectory(output);boolean equal=true;
        for(int frame=0;frame<120;frame++) {
            var a=expected.get(frame);var b=actual.get(frame);String prefix="frame-"+(frame+1)+"-";
            equal&=compareState(output,prefix+"state",a.state,b.state);
            equal&=compareState(output,prefix+"ram",a.ram,b.ram);
            equal&=compareState(output,prefix+"rtc",a.rtc,b.rtc);
            equal&=compareState(output,prefix+"video",a.video,b.video);
            equal&=compareState(output,prefix+"pcm",a.audio,b.audio);
        }
        return equal;
    }
    private static LibretroJniRuntime openDiagnosticCore(Path output,byte[] content)throws Exception {
        // Use the core's system declaration (need_fullpath=true). RetroArch also
        // handles a separate NES content override; it is not the JNI system contract.
        var profile=new LibretroProfile("FCEUmm","nes",true,List.of(PROFILE.deviceForPort(0),PROFILE.deviceForPort(1)),false,
                PROFILE.options(),Map.of("windows-x64",new LibretroProfile.Artifact(PROFILE.resource(),CORE_SHA,4162048)));
        var core=new LibretroJniRuntime(profile,NetplayProcess.class);
        try {
            var info=core.load(content);restoreOpens++;
            writeNew(output.resolve("runtime.txt"),"coreName=FCEUmm\ncoreVersion="+core.coreVersion()+"\ninfo="+info
                    +"\nrotation="+core.rotation()+"\ncapabilities="+core.capabilities()
                    +"\nidentity="+NetplaySaveState.hash(core.persistenceIdentity())+"\nprofile="+profile+"\n");
            check(core.coreVersion().equals("(SVN) ac0d00a"),"runtime version matches fixed official reference commit");
            check(info.width()>0&&info.height()>0&&info.sampleRate()==48000&&core.rotation()==0,"valid 48kHz software video metadata");
            return core;
        }catch(Throwable failure){core.close();throw failure;}
    }
    private static boolean restoreCase(String name,boolean readControllers)throws Exception {
        Path output=Files.createDirectory(root.resolve(name));byte[] content=rom(readControllers);
        Files.write(output.resolve("original.nes"),content,StandardOpenOption.CREATE_NEW);
        boolean exact=true;byte[] seed,seedRam,seedRtc;List<Sample> expected;
        Path source=Files.createDirectory(output.resolve("source"));
        try(var core=openDiagnosticCore(source,content)) {
            for(int i=0;i<60;i++)core.run(controls(0,0),3);
            seed=core.serialize();seedRam=core.memory(0);seedRtc=core.memory(1);
            Files.write(source.resolve("seed.bin"),seed,StandardOpenOption.CREATE_NEW);
            Files.write(source.resolve("seed-ram.bin"),seedRam,StandardOpenOption.CREATE_NEW);
            check(seed.length>0&&seedRam.length==8192&&(seedRam[0]&255)==0x5a,"original ROM state and battery SRAM marker");
            core.restore(seed);exact&=compareState(source,"immediate-state",seed,core.serialize());
            exact&=compareState(source,"immediate-ram",seedRam,core.memory(0));
            expected=replay(core);core.restore(seed);
            exact&=compareReplay(output.resolve("same-instance-replay120"),expected,replay(core));
            check(expected.stream().anyMatch(s->{for(byte value:s.audio)if(value!=0)return true;return false;}),"real nonzero PCM");
            check(expected.stream().allMatch(s->s.video.length>0),"real software video every frame");
            if(readControllers) {
                Set<Integer> pads=new HashSet<>();for(var sample:expected)pads.add((sample.ram[1]&1)|((sample.ram[2]&1)<<1));
                check(pads.equals(Set.of(0,1,2,3)),"both independent pad inputs reached original ROM SRAM");
            }
        }finally{check(NativeLibretroBridge.freeSlotsIfLoaded()==4,name+" all four JNI slots available after source close");}
        // A peer has independent non-serialized globals. Capture both its first frame
        // and 120 frames after restore at four warmup ages, without altering state bytes.
        for(int warmup:new int[]{0,1,60,257}) {
            Path fresh=Files.createDirectory(output.resolve("fresh-"+warmup));
            try(var core=openDiagnosticCore(fresh,content)) {
                for(int i=0;i<warmup;i++)core.run(controls(((i+1)%2)*256,(((i+1)/2)%2)*256),3);
                core.restore(seed);
                exact&=compareState(fresh,"immediate-state",seed,core.serialize());
                exact&=compareState(fresh,"immediate-ram",seedRam,core.memory(0));
                exact&=compareState(fresh,"immediate-rtc",seedRtc,core.memory(1));
                exact&=compareReplay(fresh.resolve("replay120"),expected,replay(core));
            }finally{check(NativeLibretroBridge.freeSlotsIfLoaded()==4,name+" all four JNI slots available after fresh-"+warmup+" close");}
        }
        return exact;
    }
    private static void restoreDiagnostic()throws Exception {
        boolean reads=restoreCase("reads-controllers",true);
        boolean control=restoreCase("no-controller-reads",false);
        check(sha(productionJar).equals(originalJarSha),"diagnostic JAR unchanged");
        writeNew(root.resolve("restore-diagnostic.txt"),"readControllersExact="+reads+"\nnoControllerReadsExact="+control
                +"\ncomparisons="+restoreComparisons+"\ndifferences="+restoreDifferences+"\nnativeOpens="+restoreOpens
                +"\nDiagnostic uses the original packaged FCEUmm through isolated JNI. Full state, SRAM/RTC, video and PCM replay across 120 frames; all differences retained. Not RetroArch acceptance. No state normalization, core patch, or CRC exemption.\n");
        if(!reads||!control)throw new AssertionError("Unmodified packaged FCEUmm restore/replay differs; see per-case byte comparisons");
        check(restoreComparisons==6028&&restoreOpens==10,"all restore comparisons and independent instances completed");
    }
    private static void explainInitialStates(String name,byte[] syncRam,Map<String,List<Long>> targets)throws Exception {
        Path output=Files.createDirectory(root.resolve(name+"-initial-state-model"));
        Files.write(output.resolve("observed-sync-sram.bin"),syncRam,StandardOpenOption.CREATE_NEW);
        var matches=new HashMap<String,Integer>();
        try(var core=openDiagnosticCore(output,ROM)){
            check(core.memory(0).length==syncRam.length,name+" actual SYNC SRAM fits the unchanged official core");
            // This mirrors the observed pre-LOAD SYNC memory operation through the
            // existing JNI API. It never changes received bytes or the compared CRC.
            for(int frame=0;frame<=120;frame++){
                byte[] seed=core.serialize();core.restoreSaveMemory(new LibretroSaveMemory(syncRam,core.memory(1)));
                byte[] state=core.serialize();var crc=new java.util.zip.CRC32();crc.update(state);
                String value=String.format(Locale.ROOT,"%08x",crc.getValue()),hash=NetplaySaveState.hash(state);
                append(output.resolve("model-comparisons.tsv"),frame+"\t"+value+"\t"+hash+"\n");
                if(targets.containsKey(value)){
                    matches.merge(value,1,Integer::sum);Files.write(output.resolve("reproduced-frame-"+frame+".bin"),state,StandardOpenOption.CREATE_NEW);
                    append(output.resolve("explanations.tsv"),targets.get(value)+"\t"+value+"\t"+frame+"\t"+state.length+"\t"+hash+"\t"+NetplaySaveState.hash(syncRam)+"\n");
                }
                core.restore(seed);if(frame<120)core.run(controls(0,0),3);
            }
            for(String target:targets.keySet())check(matches.getOrDefault(target,0)==1,name+" initial complete-state CRC32 "+target+" uniquely reproduced with actual SYNC SRAM");
        }finally{check(NativeLibretroBridge.freeSlotsIfLoaded()==4,name+" initial model releases all JNI slots");}
        writeNew(output.resolve("scope.txt"),"All initial CRC differences remain in the original logs. Each has one complete-state CRC32 reproduction using the exact observed SYNC SRAM and the unmodified packaged core. Saved SHA256 identifies the reproduced state; remote full-state bytes were not captured. Later state segments always require strict same-frame HOST CRC equality.\n");
    }
    private static void scenario(int generation)throws Exception {
        try(var room=new Room(generation)) {
            room.host=room.open(HOST,0,"host");
            if(generation>0)check(room.host.process.diagnostic().contains("Checkpoint restored before Netplay initialization"),"native accepted durable seed before Netplay initialization");
            room.player=room.open(PLAYER,1,"player");room.drain(1500);
            room.observer=room.open(OBSERVER,-1,"observer");
            room.rejectSave(room.player);room.rejectSave(room.observer);
            room.observer.process.inputRetroPad(4095);
            var pictures=new HashSet<String>();
            for(int mask:new int[]{0,1,2,3,0}){room.assertPads(mask&1,(mask>>>1)&1,"pads="+mask);if(mask!=0||pictures.isEmpty())pictures.add(room.host.picture);}
            check(pictures.size()==4,"P1 and P2 independently produce four distinct pictures; observer input ignored");
            // Leave a final released state; observer remains deliberately inputting all buttons.
            int initial=room.saves.commits.get();
            if(generation==0){
                // Timer starts at native readiness, not Room construction. A slow startup may
                // already have crossed its first deadline; await the next bounded periodic save.
                room.until("periodic automatic durable commit without manual requests",()->room.saves.commits.get()>initial);
                room.player.close();room.drain(1200);
                room.player=room.open(PLAYER,1,"player-rejoined");room.rejectSave(room.player);
                room.assertPads(0,1,"rejoined P2");room.assertPads(0,0,"rejoined P2 release");
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);int input=0;
            while(System.nanoTime()<end){input^=1;room.host.process.inputRetroPad(input==0?0:256);room.player.process.inputRetroPad(input==0?256:0);room.drain(90);}
            room.assertPads(0,0,"final released inputs");room.drain(3000);room.verifyCrc(2);
            for(Run run:room.members){check(run.audioSamples>0&&run.nonzeroSamples>0,run.name+" real nonzero PCM received");run.snapshot("before-room-close");}
            int before=room.saves.commits.get();room.close();
            room.verifyProtocolAfterClose(2);
            check(room.saves.commits.get()>before&&room.saves.finishes.get()==1,"final durable commit completed before writer release");
            Files.write(root.resolve("generation-"+generation+"-checkpoint.bin"),room.saves.latest,StandardOpenOption.CREATE_NEW);
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--self-test")){selfTest();System.out.println("SELF_TEST_PASS checks="+checks.get()+"; native not started");return;}
        boolean restoreDiagnostic=args.length==2&&args[0].equals("--restore-diagnostic");
        if(args.length<1||args.length>2)throw new IllegalArgumentException("Expected NEW_OUTPUT_DIR [P2_DELAY_MS], --restore-diagnostic NEW_OUTPUT_DIR, or --self-test");
        delayMillis=args.length==2&&!restoreDiagnostic?Integer.parseInt(args[1]):0;
        if(delayMillis<0||delayMillis>150)throw new IllegalArgumentException("Delay must be 0..150 ms");
        root=Path.of(args[restoreDiagnostic?1:0]).toAbsolutePath().normalize();Files.createDirectory(root);Files.createDirectory(root.resolve("instance"));
        long start=System.nanoTime();
        try {
            selfTest();validatePackagedInputs();RuntimeWorkspace.configure(root.resolve("instance"));
            if(restoreDiagnostic){restoreDiagnostic();return;}
            Files.write(root.resolve("source-original.nes"),ROM,StandardOpenOption.CREATE_NEW);
            writeNew(root.resolve("crc-summary.tsv"),"role\tcompared\tmismatches\tnativeMode\tinitialDifferencesExplained\tstateSegments\n");
            for(int generation=0;generation<2;generation++)scenario(generation);
            check(Arrays.equals(ROM,Files.readAllBytes(root.resolve("source-original.nes"))),"original diagnostic source remains unchanged");
            check(sha(productionJar).equals(originalJarSha),"production JAR unchanged");
            try(var paths=Files.list(root.resolve("instance/game-console/runtime-sessions"))){check(paths.noneMatch(p->p.getFileName().toString().startsWith("netplay-")),"no owned Netplay workspace remains");}
            writeNew(root.resolve("result.txt"),"PASS checks="+checks.get()+" elapsedSeconds="+TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)+"\nPinned RetroArch/FCEUmm; genuine native HOST/P2/SPECTATOR; two generations; complete same-frame CRC; two-pad RAM/video; nonzero PCM; readonly observer; ordered delay="+delayMillis+" ms; save/reopen; exact child/owned workspace reclamation.\nNot Minecraft, WAN/loss, long-game, performance or all-core acceptance.\n");
            System.out.println("PASS checks="+checks.get());
        }catch(Throwable failure){
            for(Run run:allRuns)try{run.snapshot("failure");}catch(Throwable logging){failure.addSuppressed(logging);}
            var trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));writeNew(root.resolve("failure.txt"),trace.toString());throw failure;
        }
    }
}
