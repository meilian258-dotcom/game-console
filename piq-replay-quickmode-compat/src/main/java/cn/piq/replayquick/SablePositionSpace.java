package cn.piq.replayquick;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.network.Connection;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Proves flag-equivalent handling against the actual current decoder's Sable container.
 * This does not reconstruct, guess or store a missing historical packet flag.
 *
 * In reviewed Sable 2.0.5 ClientPacketListenerMixin.sable$lerp, the flag only affects
 * handling inside plot bounds or when the destination belongs to a sub-level. Outside
 * both, true and false execute the same branch, including any old-position cleanup.
 */
public final class SablePositionSpace {
    private static volatile Binding binding;
    private static volatile boolean bindingAttempted;

    /** Called synchronously before a generated quick-replay teleport enters EmbeddedChannel. */
    public static boolean outsideSubLevels(ChannelPipeline pipeline, double x, double y, double z) {
        if (pipeline == null || !(pipeline.channel() instanceof EmbeddedChannel) || !finiteCoordinates(x, y, z)) return false;
        try {
            Binding reviewed = binding();
            return reviewed != null && outsideSubLevels(x, y, z, new RuntimeView(pipeline, reviewed));
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static Binding binding() {
        if (!bindingAttempted) {
            synchronized (SablePositionSpace.class) {
                if (!bindingAttempted) {
                    try {
                        if (SableWireContract.supported()) binding = new Binding();
                    } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        // Unknown/missing implementations must keep the existing paused-normal fallback.
                    } finally {
                        bindingAttempted = true;
                    }
                }
            }
        }
        return binding;
    }

    /** Package-private test seam; the public entry point always uses the reviewed runtime binding. */
    interface View {
        boolean clientThread();
        boolean localChannel();
        Object level();
        Object receiver();
        Object receiverLevel(Object receiver);
        Object receiverConnection(Object receiver);
        Object pipelineConnection();
        Object connectionReceiver(Object connection);
        Object helper() throws ReflectiveOperationException;
        Object container(Object level) throws ReflectiveOperationException;
        boolean reviewedTypes(Object helper, Object container);
        Object containerLevel(Object container) throws ReflectiveOperationException;
        boolean inBounds(Object container, double x, double y, double z) throws ReflectiveOperationException;
        Object containing(Object helper, Object level, double x, double y, double z) throws ReflectiveOperationException;
    }

    static boolean outsideSubLevels(double x, double y, double z, View view) {
        if (!finiteCoordinates(x, y, z) || view == null) return false;
        try {
            if (!view.clientThread() || !view.localChannel()) return false;
            Object receiver = view.receiver();
            if (receiver == null) return false;
            // The active packet receiver, not just Minecraft.level, owns the decoder's world.
            Object level = view.receiverLevel(receiver), connection = view.receiverConnection(receiver);
            if (level == null || connection == null || view.level() != level
                    || view.pipelineConnection() != connection || view.connectionReceiver(connection) != receiver) return false;
            Object helper = view.helper(), container = view.container(level);
            if (helper == null || container == null || !view.reviewedTypes(helper, container)
                    || view.containerLevel(container) != level) return false;
            if (view.inBounds(container, x, y, z) || view.containing(helper, level, x, y, z) != null) return false;
            // No cached world, default plot geometry, entity state or mutable historical flag is used.
            return view.clientThread() && view.localChannel() && view.receiver() == receiver
                    && view.receiverLevel(receiver) == level && view.receiverConnection(receiver) == connection
                    && view.pipelineConnection() == connection && view.connectionReceiver(connection) == receiver
                    && view.level() == level && view.container(level) == container
                    && view.helper() == helper && view.containerLevel(container) == level;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static boolean finiteCoordinates(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && x >= Integer.MIN_VALUE && x < Integer.MAX_VALUE
                && y >= Integer.MIN_VALUE && y < Integer.MAX_VALUE
                && z >= Integer.MIN_VALUE && z < Integer.MAX_VALUE;
    }

    /** Reflective so an absent Sable dependency never links its classes into this mod. */
    private static final class Binding {
        private final Class<?> helperType, containerType;
        private final Field helperField;
        private final Method getContainer, getLevel, inBounds, getContaining;

        Binding() throws ReflectiveOperationException {
            ClassLoader loader = SablePositionSpace.class.getClassLoader();
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable", false, loader);
            helperType = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion", false, loader);
            Class<?> baseContainer = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer", false, loader);
            containerType = Class.forName("dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer", false, loader);
            helperField = sable.getField("HELPER");
            getContainer = baseContainer.getMethod("getContainer", ClientLevel.class);
            getLevel = baseContainer.getMethod("getLevel");
            inBounds = baseContainer.getMethod("inBounds", BlockPos.class);
            getContaining = helperType.getMethod("getContaining", Level.class, Position.class);
        }

    }

    /** Per-dispatch receiver context; no pipeline, receiver or world is cached between packets. */
    private static final class RuntimeView implements View {
        private final ChannelPipeline pipeline;
        private final Binding binding;

        RuntimeView(ChannelPipeline pipeline, Binding binding) { this.pipeline = pipeline; this.binding = binding; }
        @Override public boolean clientThread() { return Minecraft.getInstance().isSameThread(); }
        @Override public boolean localChannel() { return pipeline.channel() instanceof EmbeddedChannel; }
        @Override public Object level() { return Minecraft.getInstance().level; }
        @Override public Object receiver() { return Minecraft.getInstance().getConnection(); }
        @Override public Object receiverLevel(Object receiver) { return ((ClientPacketListener) receiver).getLevel(); }
        @Override public Object receiverConnection(Object receiver) { return ((ClientPacketListener) receiver).getConnection(); }
        @Override public Object pipelineConnection() { return pipeline.get(Connection.class); }
        @Override public Object connectionReceiver(Object connection) { return ((Connection) connection).getPacketListener(); }
        @Override public Object helper() throws ReflectiveOperationException { return binding.helperField.get(null); }
        @Override public Object container(Object level) throws ReflectiveOperationException { return binding.getContainer.invoke(null, level); }
        @Override public boolean reviewedTypes(Object helper, Object container) {
            // An unreviewed subclass may override these methods; never silently accept it.
            return helper.getClass() == binding.helperType && container.getClass() == binding.containerType;
        }
        @Override public Object containerLevel(Object container) throws ReflectiveOperationException { return binding.getLevel.invoke(container); }
        @Override public boolean inBounds(Object container, double x, double y, double z) throws ReflectiveOperationException {
            return (boolean) binding.inBounds.invoke(container, BlockPos.containing(x, y, z));
        }
        @Override public Object containing(Object helper, Object level, double x, double y, double z) throws ReflectiveOperationException {
            return binding.getContaining.invoke(helper, level, new Vec3(x, y, z));
        }
    }

    private SablePositionSpace() {}
}
