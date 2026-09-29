package cn.piq.fcarcade.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Optional Sable Companion bridge. No Sable types enter our class signatures or constant pool. */
public final class HomeShipSpace {
    public static final int MAX_RAY_SHIPS = 16;
    public static final Frame GROUND = new Frame(null, Vec3.ZERO, Vec3.ZERO, new Quaterniond());
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("piq_fc_arcade/ship");
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final Access ACCESS = load(HomeShipSpace.class.getClassLoader());
    private HomeShipSpace() {}

    /** Immutable, rigid pose snapshot: world = rotation * (storage - pivot) + position. */
    public static final class Frame {
        private final UUID id;
        private final Vec3 position, pivot;
        private final Quaterniond rotation;
        public Frame(UUID id, Vec3 position, Vec3 pivot, Quaterniondc rotation) {
            if (!finite(position) || !finite(pivot) || rotation == null
                    || !Double.isFinite(rotation.lengthSquared()) || Math.abs(rotation.lengthSquared() - 1) > .00001)
                throw new IllegalArgumentException("Invalid rigid ship pose");
            this.id = id; this.position = position; this.pivot = pivot; this.rotation = new Quaterniond(rotation);
        }
        public UUID id() { return id; }
        public boolean ship() { return id != null; }
        /** Copy for client screen poses; callers cannot mutate this immutable snapshot. */
        public Quaterniond rotation() { return new Quaterniond(rotation); }
        public Vec3 toWorld(Vec3 storage) {
            if (!finite(storage)) throw new IllegalArgumentException("Nonfinite position");
            var p = rotation.transform(new Vector3d(storage.x - pivot.x, storage.y - pivot.y, storage.z - pivot.z));
            return new Vec3(p.x + position.x, p.y + position.y, p.z + position.z);
        }
        public Vec3 toStorage(Vec3 world) {
            if (!finite(world)) throw new IllegalArgumentException("Nonfinite position");
            var p = rotation.transformInverse(new Vector3d(world.x - position.x, world.y - position.y, world.z - position.z));
            return new Vec3(p.x + pivot.x, p.y + pivot.y, p.z + pivot.z);
        }
        public Vec3 directionToStorage(Vec3 world) {
            if (!finite(world)) throw new IllegalArgumentException("Nonfinite direction");
            var p = rotation.transformInverse(new Vector3d(world.x, world.y, world.z));
            return new Vec3(p.x, p.y, p.z);
        }
    }
    public static boolean finite(Vec3 p) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
    }
    /** Null means unavailable/unsupported, never silently interpreted as ordinary ground. */
    public static Frame at(Level level, BlockPos storage) { return at(level, storage, Float.NaN); }
    public static Frame at(Level level, BlockPos storage, float partialTick) {
        if (level == null || storage == null) return null;
        try { return ACCESS.at(level, storage, partialTick); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); return null; }
    }
    public static boolean same(Frame a, Frame b) { return a != null && b != null && Objects.equals(a.id, b.id); }
    /** On ordinary ground all original device types are unchanged; ship support is FC-only. */
    public static boolean canLink(boolean famicom, Frame console, Frame television) {
        return same(console, television) && (!console.ship() || famicom);
    }
    public static boolean bindingMatches(Frame current, UUID boundShip) {
        return current != null && Objects.equals(current.id, boundShip);
    }
    /** World point -> storage for an existing BER; never applies the ship transform to its PoseStack again. */
    public static Vec3 localPoint(Level level, BlockPos anchor, Vec3 world, float partialTick) {
        var frame = at(level, anchor, partialTick);
        return frame == null || !finite(world) ? null : frame.toStorage(world);
    }
    public static Vec3 interpolatedEye(Entity player, float partialTick) {
        if (player == null) return null;
        try { return ACCESS.eye(player,partialTick); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); return null; }
    }
    /** Includes main world and every intersecting ship, with a strict bound and no chunk loads. */
    public static List<Frame> rayFrames(Level level, Vec3 from, Vec3 to) {
        if (level == null || !finite(from) || !finite(to) || from.distanceToSqr(to) > 256.0001) return List.of();
        try { return ACCESS.rayFrames(level, from, to); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); return List.of(); }
    }
    private static void warn(Throwable error) {
        if (WARNED.compareAndSet(false,true)) LOG.warn("FC experimental ship bridge unavailable; interactions fail closed. Expected Sable 2.0.3 / Companion 1.6.0; assemble vessel before placing devices.",error);
    }
    interface Access {
        Frame at(Level level, BlockPos pos, float partial) throws ReflectiveOperationException;
        List<Frame> rayFrames(Level level, Vec3 from, Vec3 to) throws ReflectiveOperationException;
        default Vec3 eye(Entity player,float partial) throws ReflectiveOperationException { return player.getEyePosition(partial); }
    }
    static Access load(ClassLoader loader) {
        try {
            Class.forName("dev.ryanhcode.sable.Sable", false, loader);
            return new ReflectiveAccess(loader);
        } catch (ClassNotFoundException missing) {
            // Distinguish absent Sable from a present Sable with a missing/incompatible Companion.
            try { Class.forName("dev.ryanhcode.sable.Sable", false, loader); }
            catch (ClassNotFoundException absent) { return new FixedAccess(true); }
            catch (LinkageError broken) { warn(broken); return new FixedAccess(false); }
            warn(missing);
            return new FixedAccess(false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | java.util.ServiceConfigurationError error) { warn(error); return new FixedAccess(false); }
    }
    private record FixedAccess(boolean ground) implements Access {
        public Frame at(Level l, BlockPos p, float partial) { return ground ? GROUND : null; }
        public List<Frame> rayFrames(Level l, Vec3 a, Vec3 b) { return ground ? List.of(GROUND) : List.of(); }
    }
    private static final class ReflectiveAccess implements Access {
        private final Object api;
        private final Method containing, inGrid, intersecting, id, logical, render, position, pivot, orientation, scale, eye;
        private final Class<?> clientSublevel;
        private final Constructor<?> bounds;
        ReflectiveAccess(ClassLoader loader) throws ReflectiveOperationException {
            var companion = Class.forName("dev.ryanhcode.sable.companion.SableCompanion", true, loader);
            var sublevel = Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess", false, loader);
            clientSublevel = Class.forName("dev.ryanhcode.sable.companion.ClientSubLevelAccess", false, loader);
            var pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc", false, loader);
            var box = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d", false, loader);
            var boxView = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc", false, loader);
            api = Objects.requireNonNull(companion.getField("INSTANCE").get(null));
            if (!Class.forName("dev.ryanhcode.sable.ActiveSableCompanion",false,loader).isInstance(api))
                throw new IllegalStateException("Sable active Companion provider is not available");
            containing = companion.getMethod("getContaining", Level.class, Vec3i.class);
            inGrid = companion.getMethod("isInPlotGrid", Level.class, Vec3i.class);
            intersecting = companion.getMethod("getAllIntersecting", Level.class, boxView);
            bounds = box.getConstructor(double.class,double.class,double.class,double.class,double.class,double.class);
            id = sublevel.getMethod("getUniqueId"); logical = sublevel.getMethod("logicalPose");
            render = clientSublevel.getMethod("renderPose", float.class);
            position = pose.getMethod("position"); pivot = pose.getMethod("rotationPoint");
            orientation = pose.getMethod("orientation"); scale = pose.getMethod("scale");
            eye = companion.getMethod("getEyePositionInterpolated",Entity.class,float.class);
        }
        public Vec3 eye(Entity player,float partial) throws ReflectiveOperationException {
            return (Vec3)eye.invoke(api,player,partial);
        }
        public Frame at(Level level, BlockPos pos, float partial) throws ReflectiveOperationException {
            var sub = containing.invoke(api, level, pos);
            if (sub == null) return Boolean.TRUE.equals(inGrid.invoke(api, level, pos)) ? null : GROUND;
            return snapshot(sub, partial);
        }
        private Frame snapshot(Object sub, float partial) throws ReflectiveOperationException {
            var value = Float.isFinite(partial) && clientSublevel.isInstance(sub)
                    ? render.invoke(sub, Math.clamp(partial, 0, 1)) : logical.invoke(sub);
            var s = (Vector3dc) scale.invoke(value);
            // First release is a rigid body, not arbitrary scaling or deformable/parent transforms.
            if (s == null || Math.abs(s.x()-1) > .000001 || Math.abs(s.y()-1) > .000001 || Math.abs(s.z()-1) > .000001
                    || !Double.isFinite(s.x()+s.y()+s.z())) return null;
            var p = (Vector3dc) position.invoke(value); var r = (Vector3dc) pivot.invoke(value);
            return new Frame(Objects.requireNonNull((UUID) id.invoke(sub)), new Vec3(p.x(),p.y(),p.z()),
                    new Vec3(r.x(),r.y(),r.z()), (Quaterniondc) orientation.invoke(value));
        }
        public List<Frame> rayFrames(Level level, Vec3 from, Vec3 to) throws ReflectiveOperationException {
            var result = new ArrayList<Frame>(); result.add(GROUND);
            var values = (Iterable<?>) intersecting.invoke(api, level, bounds.newInstance(
                    Math.min(from.x,to.x)-.001,Math.min(from.y,to.y)-.001,Math.min(from.z,to.z)-.001,
                    Math.max(from.x,to.x)+.001,Math.max(from.y,to.y)+.001,Math.max(from.z,to.z)+.001));
            int count = 0;
            for (var sub : values) {
                if (++count > MAX_RAY_SHIPS) return List.of();
                var frame = snapshot(sub, Float.NaN); if (frame == null) return List.of();
                result.add(frame);
            }
            return List.copyOf(result);
        }
    }
}
