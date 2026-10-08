// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Original mock binaries in a fresh JVM only; never package in the mod. */
public final class RuntimeDependencyProbe {
    static native String modulePath(String name);
    static void check(boolean value,String detail) { if(!value)throw new AssertionError(detail); }
    interface Io { void run() throws Exception; }
    static void rejects(Io task)throws Exception {
        try { task.run();throw new AssertionError("Expected IOException"); } catch(IOException expected) { }
    }
    static String sha(Path path)throws Exception {return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    static long marks(Path marker)throws IOException {return Files.exists(marker)?Files.size(marker):0;}
    static void retained(Path file)throws Exception {
        check(Path.of(modulePath(file.toString())).toRealPath().equals(file.toRealPath()),"Wrong retained module path");
        rejects(()->Files.newByteChannel(file,StandardOpenOption.WRITE).close());
    }
    static void openRejected(Path work,String reason)throws Exception {
        long token=NativeLibretroBridge.reserve();
        try {
            NativeLibretroBridge.openReserved(token,work.resolve("core-entry.dll").toString(),
                    work.resolve("game.bin").toString(),work.toString(),work.toString(),
                    "PIQ mock",false,new int[]{1},new String[0],0);
            throw new AssertionError("Expected core open rejection");
        } catch(IOException expected) { check(expected.getMessage().contains(reason),"Wrong core rejection: "+expected); }
        check(!NativeLibretroBridge.reservationHeld(token)&&NativeLibretroBridge.availableSlots()==4,
                "Rejected open did not release its reservation");
    }
    public static void main(String[] args)throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        System.load(Path.of(args[1]).toAbsolutePath().toString());
        Path work=Path.of(args[2]).toAbsolutePath(), marker=work.resolve("entry.marker");String mode=args[3];
        Path cpp=work.resolve("libc++.dll"),unwind=work.resolve("libunwind.dll");
        check(NativeLibretroBridge.abiVersion()==2,"Existing ABI changed");
        check(NativeLibretroBridge.runtimeDependencyApiVersion()==1,"Runtime API");
        check(NativeLibretroBridge.availableSlots()==4,"Runtime unexpectedly owns session slot");
        String[] paths={cpp.toString()}, hashes={sha(cpp)};
        if(mode.equals("success") || mode.equals("concurrent") || mode.equals("pair")) {
            if(mode.equals("pair")) {paths=new String[]{unwind.toString(),cpp.toString()};hashes=new String[]{sha(unwind),sha(cpp)};}
            if(mode.equals("concurrent")) {
                final String[] p=paths,h=hashes;var gate=new CountDownLatch(1);var success=new AtomicInteger();var errors=new AtomicReference<Throwable>();
                Runnable call=()->{try{gate.await();NativeLibretroBridge.retainRuntimeDependencies(p,h);success.incrementAndGet();}catch(IOException expected){}catch(Throwable error){errors.compareAndSet(null,error);}};
                Thread a=new Thread(call),b=new Thread(call);a.start();b.start();gate.countDown();a.join(3000);b.join(3000);
                check(!a.isAlive()&&!b.isAlive()&&errors.get()==null&&success.get()>=1,"Concurrent initializer failed");
            } else NativeLibretroBridge.retainRuntimeDependencies(paths,hashes);
            long count=paths.length;check(marks(marker)==count,"Entry point executed more than once");
            for(int i=0;i<8;++i)NativeLibretroBridge.retainRuntimeDependencies(paths,hashes);
            check(marks(marker)==count,"Idempotent retain re-executed entry point");retained(cpp);if(count==2)retained(unwind);
            long token=NativeLibretroBridge.reserve();NativeLibretroBridge.close(token);retained(cpp);
            check(NativeLibretroBridge.availableSlots()==4,"Reservation regression");
        } else if(mode.equals("late-foreign") || mode.equals("ready-open")) {
            NativeLibretroBridge.retainRuntimeDependencies(paths,hashes);retained(cpp);
            check(marks(marker)==1,"Retained fixture did not load once");
            if(mode.equals("late-foreign")) {
                System.load(work.resolve("foreign/libc++.dll").toString());
                check(marks(marker)==2,"Second same-basename fixture did not actually load");
                openRejected(work,"Conflicting runtime DLL");
                openRejected(work,"Runtime dependencies unavailable before core open");
                final String[] p=paths,h=hashes;rejects(()->NativeLibretroBridge.retainRuntimeDependencies(p,h));
                check(marks(marker)==2,"Core entry point executed after runtime conflict");retained(cpp);
            } else {
                // This synthetic DLL intentionally lacks libretro exports: its
                // DllMain marker proves Ready passed the guard into LoadLibrary.
                openRejected(work,"Missing libretro export");
                check(marks(marker)==2,"Valid Ready state blocked the core entry point");
                NativeLibretroBridge.retainRuntimeDependencies(paths,hashes);retained(cpp);
            }
        } else if(mode.equals("failed-open")) {
            rejects(()->NativeLibretroBridge.retainRuntimeDependencies(new String[]{cpp.toString()},new String[]{"0".repeat(64)}));
            openRejected(work,"Runtime dependencies unavailable before core open");
            check(marks(marker)==0,"Core entry point executed after initialization failure");
        } else if(mode.equals("second-failure")) {
            rejects(()->NativeLibretroBridge.retainRuntimeDependencies(new String[]{unwind.toString(),cpp.toString()},new String[]{sha(unwind),sha(cpp)}));
            check(marks(marker)==2,"Second entry point was not reached once");retained(unwind);
            rejects(()->NativeLibretroBridge.retainRuntimeDependencies(new String[]{unwind.toString()},new String[]{sha(unwind)}));
            check(marks(marker)==2,"Failed initializer retried");
        } else if(mode.equals("foreign")) {
            Path foreign=work.resolve("foreign/libc++.dll");System.load(foreign.toString());
            check(marks(marker)==1,"Foreign fixture did not load");
            final String[] p=paths,h=hashes;rejects(()->NativeLibretroBridge.retainRuntimeDependencies(p,h));
            check(marks(marker)==1,"Conflicting candidate entry point executed");
            check(Path.of(modulePath("libc++.dll")).toRealPath().equals(foreign.toRealPath()),"Foreign module changed");
        } else if(mode.equals("identity-change")) {
            NativeLibretroBridge.retainRuntimeDependencies(paths,hashes);retained(cpp);
            rejects(()->NativeLibretroBridge.retainRuntimeDependencies(new String[]{cpp.toString()},new String[]{"0".repeat(64)}));
            final String[] p=paths,h=hashes;rejects(()->NativeLibretroBridge.retainRuntimeDependencies(p,h));
            check(marks(marker)==1,"Sticky ready failure reloaded runtime");retained(cpp);
        } else {
            if(mode.equals("wrong-sha")) hashes[0]="0".repeat(64);
            if(mode.equals("relative")) paths[0]="libc++.dll";
            if(mode.equals("unknown-name")) {paths[0]=work.resolve("other.dll").toString();hashes[0]=sha(Path.of(paths[0]));}
            if(mode.equals("second-invalid")) {paths=new String[]{unwind.toString(),cpp.toString()};hashes=new String[]{sha(unwind),"0".repeat(64)};}
            final String[] p=paths,h=hashes;rejects(()->NativeLibretroBridge.retainRuntimeDependencies(p,h));
            check(marks(marker)==0,"Rejected input executed a DLL entry point");
            rejects(()->NativeLibretroBridge.retainRuntimeDependencies(new String[]{cpp.toString()},new String[]{sha(cpp)}));
            check(marks(marker)==0,"Failed initializer was not sticky");
        }
        check(NativeLibretroBridge.availableSlots()==4,"Runtime failure consumed a session slot");
        System.out.println("RUNTIME_DEPENDENCY_OK mode="+mode+" entries="+marks(marker));
    }
}
