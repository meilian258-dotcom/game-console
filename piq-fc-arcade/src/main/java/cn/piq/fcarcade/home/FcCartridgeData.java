package cn.piq.fcarcade.home;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/** Small references only: ROMs and cover PNGs never enter item NBT. Server writes only. */
public final class FcCartridgeData {
    public static final String DATA_KEY = "piq_fc_home_cartridge";
    private FcCartridgeData() {}
    public static boolean isCartridge(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof FcCartridgeItem;
    }
    public static boolean isBoard(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof FcCartridgeBoardItem;
    }
    public static boolean isShell(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof FcCartridgeShellItem;
    }
    public static boolean isPlayable(ItemStack stack) { return isCartridge(stack) || isBoard(stack); }
    /** Refuse lossy crafting, but do not confuse inherited item defaults with explicit component edits. */
    public static boolean supportsAssembly(ItemStack stack) {
        if (!(isPlayable(stack) || isShell(stack))) return false;
        var patches = stack.getComponentsPatch().entrySet().stream()
                .map(entry -> new CartridgeAssemblyMetadata.Change(entry.getKey() == DataComponents.CUSTOM_DATA, entry.getValue().isPresent())).toList();
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag own = root.getCompound(DATA_KEY);
        var fields = new java.util.HashMap<String,Integer>();
        for (String key : own.getAllKeys()) fields.put(key, (int) own.getTagType(key));
        var kind = isCartridge(stack) ? CartridgeAssemblyMetadata.Kind.WHOLE
                : isBoard(stack) ? CartridgeAssemblyMetadata.Kind.BOARD : CartridgeAssemblyMetadata.Kind.SHELL;
        if (!CartridgeAssemblyMetadata.supported(patches, root.getAllKeys(), root.contains(DATA_KEY,10), fields, kind)) return false;
        if (own.contains("id") && !own.hasUUID("id")) return false;
        if (!isCartridge(stack) && !own.isEmpty() && !own.hasUUID("id")) return false;
        try {
            if (own.contains("rom")) CartridgeLimits.hashOrEmpty(own.getString("rom"));
            if (own.contains("cover")) CartridgeLimits.hashOrEmpty(own.getString("cover"));
            for (String key : new String[]{"title","board_title"}) if (own.contains(key)
                    && !CartridgeLimits.cleanTitle(own.getString(key)).equals(own.getString(key))) return false;
            storedVariant(stack); assemblyRevision(stack);
            if(own.contains("save_mode")&&(own.getInt("save_mode")<0||own.getInt("save_mode")>2))return false;
            return true;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static CompoundTag data(ItemStack stack) {
        return isPlayable(stack) || isShell(stack) ? stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getCompound(DATA_KEY) : new CompoundTag();
    }
    public static String romSha(ItemStack stack) { return isPlayable(stack) ? readHash(data(stack), "rom") : ""; }
    public static String coverSha(ItemStack stack) { return isBoard(stack) ? "" : readHash(data(stack), "cover"); }
    public static String title(ItemStack stack) {
        if (!isCartridge(stack)) return "";
        String value = data(stack).getString("title");
        return value.length() <= CartridgeLimits.MAX_TITLE && value.chars().noneMatch(Character::isISOControl)
                ? value : "";
    }
    public static UUID id(ItemStack stack) {
        CompoundTag data = data(stack);
        return data.hasUUID("id") ? data.getUUID("id") : null;
    }
    /** -1 is an untouched legacy cartridge, not a live ROM-wide setting. */
    public static int storedSaveMode(ItemStack stack){return data(stack).contains("save_mode",3)?data(stack).getInt("save_mode"):-1;}
    public static cn.piq.fcarcade.rom.RomSaveMode saveMode(ItemStack stack){int mode=storedSaveMode(stack);return cn.piq.fcarcade.rom.RomSaveMode.fromId(mode>=0&&mode<=2?mode:0);}
    public static boolean hasSavedProgress(ItemStack stack){return data(stack).getBoolean("saved");}
    public static void setSaveMode(ItemStack stack,cn.piq.fcarcade.rom.RomSaveMode mode){
        requireSingle(stack);if(!isPlayable(stack))throw new IllegalArgumentException("需要游戏卡带");
        ensureIdentity(stack);var tag=data(stack);tag.putInt("save_mode",mode.id());set(stack,tag);
    }
    public static void setSavedProgress(ItemStack stack,boolean saved){requireSingle(stack);var tag=data(stack);tag.putBoolean("saved",saved);set(stack,tag);}
    private static void copySave(ItemStack stack,CompoundTag target){writeSave(target,storedSaveMode(stack),hasSavedProgress(stack));}
    private static void writeSave(CompoundTag tag,int mode,boolean saved){if(mode>=0)tag.putInt("save_mode",mode);if(saved)tag.putBoolean("saved",true);}
    public static UUID ensureIdentity(ItemStack stack) {
        requireSingle(stack);
        UUID id = id(stack);
        if (id == null || id.equals(CartridgeAssemblyBinding.ZERO)) id = UUID.randomUUID();
        CompoundTag clean = new CompoundTag();
        clean.putUUID("id", id);
        if (isPlayable(stack)) clean.putString("rom", romSha(stack));
        if (!isBoard(stack)) clean.putString("cover", coverSha(stack));
        if (isCartridge(stack)) clean.putString("title", title(stack));
        if (isBoard(stack)) clean.putString("board_title", internalBoardTitle(stack));
        int variant = storedVariant(stack);
        if (variant >= 0) clean.putInt("board_variant", variant);
        clean.putLong("assembly_revision", assemblyRevision(stack));
        if(isPlayable(stack))copySave(stack,clean);
        set(stack, clean);
        return id;
    }
    public static void write(ItemStack stack, String romSha, String title, String coverSha) {
        requireCartridge(stack);
        String rom = CartridgeLimits.hashOrEmpty(romSha);
        String cover = CartridgeLimits.hashOrEmpty(coverSha);
        String name = CartridgeLimits.cleanTitle(title);
        UUID id = ensureIdentity(stack);
        // Rebuild our own compound, dropping unknown/oversized fields in this namespace.
        CompoundTag data = new CompoundTag();
        data.putUUID("id", id);
        data.putString("rom", rom);
        data.putString("cover", cover);
        data.putString("title", name);
        int variant = storedVariant(stack);
        if (variant >= 0) data.putInt("board_variant", variant);
        data.putLong("assembly_revision", assemblyRevision(stack));
        writeSave(data,Math.max(0,storedSaveMode(stack)),rom.equals(romSha(stack))&&hasSavedProgress(stack));
        set(stack, data);
    }
    /** Stable appearance is unrelated to ROM bytes; legacy/invalid data renders the neutral first variant. */
    public static int boardVariant(ItemStack stack) {
        try { int value = storedVariant(stack); return value >= 0 ? value : 0; }
        catch (IllegalArgumentException ignored) { return 0; }
    }
    private static int storedVariant(ItemStack stack) {
        CompoundTag tag = data(stack);
        if (!tag.contains("board_variant")) return -1;
        if (!tag.contains("board_variant", 3)) throw new IllegalArgumentException("电路板外观数据无效");
        int value = tag.getInt("board_variant");
        if (value < 0 || value >= CartridgeParts.VARIANT_COUNT) throw new IllegalArgumentException("电路板外观数据无效");
        return value;
    }
    public static long assemblyRevision(ItemStack stack) {
        CompoundTag tag = data(stack);
        if (!tag.contains("assembly_revision")) return 0;
        if (!tag.contains("assembly_revision", 4) || tag.getLong("assembly_revision") < 0)
            throw new IllegalArgumentException("卡带拆装版本无效");
        return tag.getLong("assembly_revision");
    }
    public static CartridgeParts.Whole whole(ItemStack stack) {
        requireCartridge(stack);
        return new CartridgeParts.Whole(id(stack), romSha(stack), title(stack), coverSha(stack), storedVariant(stack), assemblyRevision(stack),storedSaveMode(stack),hasSavedProgress(stack));
    }
    public static CartridgeParts.Board board(ItemStack stack) {
        requireSingle(stack);
        if (!isBoard(stack)) throw new IllegalArgumentException("必须持有 FC 电路板");
        return new CartridgeParts.Board(id(stack), romSha(stack), internalBoardTitle(stack), storedVariant(stack), assemblyRevision(stack),storedSaveMode(stack),hasSavedProgress(stack));
    }
    private static String internalBoardTitle(ItemStack stack) { return CartridgeLimits.cleanTitle(data(stack).getString("board_title")); }
    public static CartridgeParts.Shell shell(ItemStack stack) {
        requireSingle(stack);
        if (!isShell(stack)) throw new IllegalArgumentException("必须持有 FC 空外壳");
        return new CartridgeParts.Shell(id(stack), coverSha(stack));
    }
    public static void writeWhole(ItemStack stack, CartridgeParts.Whole whole) {
        requireCartridge(stack);
        CompoundTag tag = identity(whole.id(), whole.variant(), whole.revision());
        tag.putString("rom", whole.rom()); tag.putString("cover", whole.cover()); tag.putString("title", whole.title());
        writeSave(tag,whole.saveMode(),whole.saved());
        set(stack, tag);
    }
    public static void writeBoard(ItemStack stack, CartridgeParts.Board board) {
        if (!isBoard(stack) || stack.getCount() != 1) throw new IllegalArgumentException("电路板数量无效");
        CompoundTag tag = identity(board.id(), board.variant(), board.revision());
        tag.putString("rom", board.rom()); tag.putString("board_title", board.internalTitle());writeSave(tag,board.saveMode(),board.saved()); set(stack, tag);
    }
    public static void writeShell(ItemStack stack, CartridgeParts.Shell shell) {
        if (!isShell(stack) || stack.getCount() != 1) throw new IllegalArgumentException("空外壳数量无效");
        CompoundTag tag = new CompoundTag(); tag.putUUID("id", shell.id()); tag.putString("cover", shell.cover()); set(stack, tag);
    }
    private static CompoundTag identity(UUID id, int variant, long revision) {
        CompoundTag tag = new CompoundTag(); tag.putUUID("id", id); tag.putLong("assembly_revision", revision);
        if (variant >= 0) tag.putInt("board_variant", variant);
        return tag;
    }
    private static String readHash(CompoundTag data, String key) {
        String value = data.getString(key);
        return CartridgeLimits.validHash(value) ? value : "";
    }
    private static void requireCartridge(ItemStack stack) {
        if (!isCartridge(stack) || stack.getCount() != 1) throw new IllegalArgumentException("必须持有单张 FC 卡带");
    }
    private static void requireSingle(ItemStack stack) {
        if (!(isPlayable(stack) || isShell(stack)) || stack.getCount() != 1) throw new IllegalArgumentException("必须持有单件 FC 卡带部件");
    }
    private static void set(ItemStack stack, CompoundTag data) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        root.put(DATA_KEY, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }
}
