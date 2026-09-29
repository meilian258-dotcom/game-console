package cn.piq.fcarcade.home;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.UUID;

/** Display/binding data only; the runtime ledger, never NBT alone, grants input authority. */
public final class HomeControllerData {
    private static final String KEY = "PiqHomeController";
    /** Render-only skin family. Unknown/legacy tags use the original FC look. */
    public enum Style {
        FAMICOM("famicom"), SUBOR("subor");
        private final String id;
        Style(String id) { this.id = id; }
        public String serializedName() { return id; }
        public static Style fromId(String id) { return "subor".equals(id) ? SUBOR : FAMICOM; }
    }
    private HomeControllerData() {}
    public static boolean isController(ItemStack stack) { return !stack.isEmpty() && stack.getItem() instanceof FcControllerItem; }
    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(KEY);
    }
    public static Style style(ItemStack stack) {
        return isController(stack) ? Style.fromId(tag(stack).getString("Style")) : Style.FAMICOM;
    }
    public static int port(ItemStack stack) {
        if (!isController(stack)) return -1;
        int value = tag(stack).contains("Port") ? tag(stack).getInt("Port") : -1;
        return value == 0 || value == 1 ? value : -1;
    }
    public static UUID leaseId(ItemStack stack) {
        if (!isController(stack)) return null;
        var tag = tag(stack); return tag.hasUUID("Lease") ? tag.getUUID("Lease") : null;
    }
    /** Strict read-only receipt. Physical inventory data never grants emulator/network authority. */
    public record Receipt(UUID lease, UUID console, String dimension, long session, int port) {
        public Receipt {
            if (lease == null || console == null || lease.equals(new UUID(0, 0)) || console.equals(new UUID(0, 0))
                    || dimension == null || dimension.length() > 256
                    || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || session < 0 || port < 0 || port > 1)
                throw new IllegalArgumentException("Invalid physical controller receipt");
        }
    }
    public static Receipt receipt(ItemStack stack) {
        if (stack == null || stack.getCount() != 1 || !isController(stack)) return null;
        var data = tag(stack);
        if (!data.contains("Borrowed", net.minecraft.nbt.Tag.TAG_BYTE) || !data.getBoolean("Borrowed")
                || !data.hasUUID("Lease") || !data.hasUUID("Console")
                || !data.contains("Dimension", net.minecraft.nbt.Tag.TAG_STRING)
                || !data.contains("Session", net.minecraft.nbt.Tag.TAG_LONG)
                || !data.contains("Port", net.minecraft.nbt.Tag.TAG_INT)) return null;
        try {
            return new Receipt(data.getUUID("Lease"), data.getUUID("Console"), data.getString("Dimension"),
                    data.getLong("Session"), data.getInt("Port"));
        } catch (IllegalArgumentException invalid) { return null; }
    }
    public static UUID consoleId(ItemStack stack) {
        var value = receipt(stack); return value == null ? null : value.console();
    }
    public static net.minecraft.resources.ResourceLocation dimension(ItemStack stack) {
        var value = receipt(stack); return value == null ? null : net.minecraft.resources.ResourceLocation.parse(value.dimension());
    }
    public static long session(ItemStack stack) {
        var value = receipt(stack); return value == null ? -1 : value.session();
    }
    /** Old alpha.3 loans kept these fields even after their Lease UUID was erased. */
    public static boolean isBorrowed(ItemStack stack) {
        if (!isController(stack)) return false;
        var data = tag(stack);
        return data.getBoolean("Borrowed") || (data.hasUUID("Console") && data.contains("Session")
                && data.contains("Port") && (data.getInt("Port") == 0 || data.getInt("Port") == 1));
    }
    static void bind(ItemStack stack, HomeControllerLedger.Lease lease) {
        // P2 pickup rotates the lease, not the originating console's appearance.
        bind(stack, lease, style(stack));
    }
    static void bind(ItemStack stack, HomeControllerLedger.Lease lease, Style style) {
        var tag = new CompoundTag();
        tag.putBoolean("Borrowed", true);
        tag.putUUID("Lease", lease.id()); tag.putUUID("Console", lease.console().id());
        tag.putString("Dimension", lease.console().dimension()); tag.putInt("Port", lease.port());
        tag.putLong("Session", lease.session());
        tag.putString("Style", (style == null ? Style.FAMICOM : style).serializedName());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> data.put(KEY, tag));
    }
    static void recycle(ItemStack stack) {
        if (isBorrowed(stack)) stack.setCount(0);
    }
}
