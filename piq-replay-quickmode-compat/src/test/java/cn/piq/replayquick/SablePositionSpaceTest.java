package cn.piq.replayquick;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Checks the production proof boundary without initializing a graphical Minecraft client. */
public final class SablePositionSpaceTest {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static final class FakeView implements SablePositionSpace.View {
        Object level = new Object(), helper = new Object(), container = new Object(), containing;
        Object containerLevel = level;
        Object receiver = new Object(), connection = new Object();
        Object receiverLevel = level, receiverConnection = connection, pipelineConnection = connection, connectionReceiver = receiver;
        boolean thread = true, reviewed = true, bounds, fail, swapWorld, swapContainer, swapHelper, switchThread;
        boolean local = true, switchChannel, swapReceiver, swapReceiverLevel, swapReceiverConnection, swapPipelineConnection, swapConnectionReceiver;
        int queries;
        double seenX, seenY, seenZ;
        @Override public boolean clientThread() { if (queries > 0 && switchThread) return false; return thread; }
        @Override public boolean localChannel() { return local && !(queries > 0 && switchChannel); }
        @Override public Object level() { return level; }
        @Override public Object receiver() { return receiver; }
        @Override public Object receiverLevel(Object ignored) { return receiverLevel; }
        @Override public Object receiverConnection(Object ignored) { return receiverConnection; }
        @Override public Object pipelineConnection() { return pipelineConnection; }
        @Override public Object connectionReceiver(Object ignored) { return connectionReceiver; }
        @Override public Object helper() { return helper; }
        @Override public Object container(Object ignored) { return container; }
        @Override public boolean reviewedTypes(Object helper, Object container) { return reviewed; }
        @Override public Object containerLevel(Object ignored) { return containerLevel; }
        @Override public boolean inBounds(Object ignored, double x, double y, double z) throws ReflectiveOperationException {
            queries++; seenX=x; seenY=y; seenZ=z;
            if (fail) throw new ReflectiveOperationException("unavailable contract");
            return bounds;
        }
        @Override public Object containing(Object ignoredHelper, Object ignoredLevel, double x, double y, double z) {
            if (swapWorld) level = new Object();
            if (swapContainer) container = new Object();
            if (swapHelper) helper = new Object();
            if (swapReceiver) receiver = new Object();
            if (swapReceiverLevel) receiverLevel = new Object();
            if (swapReceiverConnection) receiverConnection = new Object();
            if (swapPipelineConnection) pipelineConnection = new Object();
            if (swapConnectionReceiver) connectionReceiver = new Object();
            return containing;
        }
    }
    public static void main(String[] args) throws ReflectiveOperationException {
        var view = new FakeView();
        check(SablePositionSpace.outsideSubLevels(-0.25, 64, 17.5, view));
        check(view.seenX == -0.25 && view.seenY == 64 && view.seenZ == 17.5);
        for (int rejection=0; rejection<25; rejection++) {
            view = new FakeView();
            switch (rejection) {
                case 0 -> view.thread=false;
                case 1 -> view.level=null;
                case 2 -> view.helper=null;
                case 3 -> view.container=null;
                case 4 -> view.reviewed=false;
                case 5 -> view.containerLevel=new Object();
                case 6 -> view.bounds=true;
                case 7 -> view.containing=new Object();
                case 8 -> view.fail=true;
                case 9 -> view.swapWorld=true;
                case 10 -> view.swapContainer=true;
                case 11 -> view.swapHelper=true;
                case 12 -> view.switchThread=true;
                case 13 -> view.receiver=null;
                case 14 -> view.receiverLevel=null;
                case 15 -> view.receiverConnection=null;
                case 16 -> view.pipelineConnection=new Object();
                case 17 -> view.connectionReceiver=new Object();
                case 18 -> view.swapReceiver=true;
                case 19 -> view.swapReceiverLevel=true;
                case 20 -> view.swapReceiverConnection=true;
                case 21 -> view.swapPipelineConnection=true;
                case 22 -> view.swapConnectionReceiver=true;
                case 23 -> view.local=false;
                case 24 -> view.switchChannel=true;
            }
            check(!SablePositionSpace.outsideSubLevels(0, 64, 0, view));
        }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                (double) Integer.MAX_VALUE, (double) Integer.MIN_VALUE - 1}) {
            view = new FakeView();
            check(!SablePositionSpace.outsideSubLevels(invalid, 0, 0, view));
            check(!SablePositionSpace.outsideSubLevels(0, invalid, 0, view));
            check(!SablePositionSpace.outsideSubLevels(0, 0, invalid, view));
            check(view.queries == 0);
        }
        check(!SablePositionSpace.outsideSubLevels(0, 0, 0, null));
        view = new FakeView();
        check(SablePositionSpace.outsideSubLevels(Integer.MIN_VALUE, 0, Integer.MAX_VALUE - 0.25, view));
        realBindingSignatures();
        System.out.println("SablePositionSpaceTest checks=" + checks
                + " PASS (production proof gates and real reflection signatures; no runtime world or graphical client)");
    }

    /**
     * Resolves the real private production Binding against actual Sable/Minecraft dependencies.
     * Its constructor only loads classes without initialization and resolves members. It must
     * not read Sable.HELPER, call Minecraft.getInstance(), construct a world, or use Unsafe.
     */
    private static void realBindingSignatures() throws ReflectiveOperationException {
        Class<?> type = Class.forName("cn.piq.replayquick.SablePositionSpace$Binding", false,
                SablePositionSpaceTest.class.getClassLoader());
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object binding = constructor.newInstance();
        check(binding.getClass() == type);
        Field helper = (Field) member(type, binding, "helperField");
        check(helper.getName().equals("HELPER") && Modifier.isStatic(helper.getModifiers())
                && Modifier.isFinal(helper.getModifiers()));
        check(helper.getDeclaringClass().getName().equals("dev.ryanhcode.sable.Sable"));
        check(helper.getType().getName().equals("dev.ryanhcode.sable.ActiveSableCompanion"));

        Method getContainer = (Method) member(type, binding, "getContainer");
        check(Modifier.isStatic(getContainer.getModifiers()));
        signature(getContainer, "getContainer", "dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer",
                "net.minecraft.client.multiplayer.ClientLevel");
        signature((Method) member(type, binding, "getLevel"), "getLevel", "net.minecraft.world.level.Level");
        signature((Method) member(type, binding, "inBounds"), "inBounds", "boolean", "net.minecraft.core.BlockPos");
        signature((Method) member(type, binding, "getContaining"), "getContaining",
                "dev.ryanhcode.sable.sublevel.SubLevel", "net.minecraft.world.level.Level", "net.minecraft.core.Position");
    }

    private static Object member(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void signature(Method method, String name, String result, String... parameters) {
        check(method.getName().equals(name));
        check(method.getReturnType().getName().equals(result));
        check(method.getParameterCount() == parameters.length);
        for (int i = 0; i < parameters.length; i++) check(method.getParameterTypes()[i].getName().equals(parameters[i]));
    }
}
