package cn.piq.sfchome.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Probe-only compilation; every production class must originate in the supplied final SFC JAR. */
public final class Alpha17SfcStartupProbe {
    private static int assertions;
    private static void check(boolean ok) {
        assertions++;
        if (!ok) throw new AssertionError("SFC final-JAR startup check " + assertions);
    }
    private static void origin(Class<?> type, Path jar) throws Exception {
        check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar));
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected final SFC JAR path");
        Path jar = Path.of(args[0]).toRealPath();
        Class<?> policy = Class.forName("cn.piq.sfchome.server.SfcHomeStartPolicy");
        Class<?> planType = Class.forName("cn.piq.sfchome.server.SfcHomeStartPolicy$Plan");
        Class<?> gateType = Class.forName("cn.piq.sfchome.server.SfcHomeStartPolicy$InteractionGate");
        for (Class<?> type : List.of(SfcStartupProgress.class, SfcStartupProgress.Stage.class, policy, planType, gateType))
            origin(type, jar);

        Method plan = method(policy, "plan", int.class, boolean.class, boolean.class);
        for (int repeat = 0; repeat < 64; repeat++) {
            for (int players = 1; players <= 2; players++) {
                for (boolean explicit : new boolean[] {false, true}) {
                    for (boolean second : new boolean[] {false, true}) {
                        String expected = explicit && players == 1 ? "SINGLE"
                                : second ? "DUAL" : explicit ? "WAIT_FOR_SECOND" : "SINGLE";
                        check(plan.invoke(null, players, explicit, second).toString().equals(expected));
                    }
                }
            }
        }
        for (int invalid : new int[] {Integer.MIN_VALUE, -1, 0, 3, 4, Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { plan.invoke(null, invalid, true, true); }
            catch (InvocationTargetException failure) { rejected = failure.getCause() instanceof IllegalArgumentException; }
            check(rejected);
        }

        Constructor<?> gateConstructor = gateType.getDeclaredConstructor();
        gateConstructor.setAccessible(true);
        Object gate = gateConstructor.newInstance();
        Method allow = method(gateType, "allow", UUID.class, long.class);
        Method expire = method(gateType, "expireBefore", long.class);
        UUID p1 = new UUID(1, 1), p2 = new UUID(2, 2);
        for (long tick = 0; tick < 512; tick++) {
            check((boolean) allow.invoke(gate, p1, tick));
            check(!(boolean) allow.invoke(gate, p1, tick));
            check(!(boolean) allow.invoke(gate, p1, tick - 1));
            check((boolean) allow.invoke(gate, p2, tick));
            check(!(boolean) allow.invoke(gate, p2, tick));
        }
        expire.invoke(gate, 511L);
        check(!(boolean) allow.invoke(gate, p1, 511L));
        expire.invoke(gate, 512L);
        check((boolean) allow.invoke(gate, p1, 511L));
        check((boolean) allow.invoke(gate, p2, 511L));

        for (int repeat = 0; repeat < 64; repeat++) {
            long[] clock = {repeat * 100_000_000_000L};
            SfcStartupProgress progress = new SfcStartupProgress(() -> clock[0]);
            check(progress.stage() == SfcStartupProgress.Stage.CACHE);
            check(!progress.message().contains("双手柄"));
            Map<SfcStartupProgress.Stage, Long> initial = progress.timings();
            check(initial.size() == 1 && initial.get(SfcStartupProgress.Stage.CACHE) == 0L);
            long total = 0;
            for (SfcStartupProgress.Stage next : SfcStartupProgress.Stage.values()) {
                SfcStartupProgress.Stage previous = progress.stage();
                long elapsed = 100_000_000L + repeat * 1_000_000L;
                clock[0] += elapsed;
                total += elapsed;
                progress.enter(next);
                check(progress.stage() == next);
                check(progress.timings().values().stream().mapToLong(Long::longValue).sum() == total);
                if (next != previous) check(progress.timings().get(previous) >= elapsed);
                if (next == SfcStartupProgress.Stage.DOWNLOAD) {
                    progress.download(1, 4); check(progress.message().contains("25%"));
                    progress.download(10, 4); check(progress.message().contains("100%"));
                } else {
                    progress.download(1, 4); check(!progress.message().contains("%"));
                }
                progress.enter(next);
                check(progress.timings().values().stream().mapToLong(Long::longValue).sum() == total);
            }
            check(initial.get(SfcStartupProgress.Stage.CACHE) == 0L);
            boolean immutable = false;
            try { initial.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable);
            progress.enter(SfcStartupProgress.Stage.CACHE);
            check(progress.stage() == SfcStartupProgress.Stage.RUNNING);
            check(progress.message().contains("游戏已启动"));
            clock[0] += 1_000_000_000L;
            check(progress.timings().values().stream().mapToLong(Long::longValue).sum() == total + 1_000_000_000L);
        }
        long[] backwardsClock = {1_000_000_000L};
        SfcStartupProgress backwards = new SfcStartupProgress(() -> backwardsClock[0]);
        backwardsClock[0] = 0L;
        check(backwards.timings().get(SfcStartupProgress.Stage.CACHE) == 0L);
        check(!backwards.message().contains("-"));
        check(assertions >= 1000);
        System.out.println("{\"ok\":true,\"assertions\":" + assertions
                + ",\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false}");
    }
}
