package cn.piq.fcarcade.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned FC/SFC cartridge permissions, never client-supplied authority. */
public final class PlayerContentAccess {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue ALL_PLAYERS = B.comment("允许所有普通玩家访问游戏库；false 时仅授权 UUID 名单。具体选用与上传权限由各自开关控制，不授予全局管理或任意下载。")
            .define("allowAllPlayers", false);
    private static final ModConfigSpec.BooleanValue ROM_UPLOADS = B.comment("允许有游戏库访问权的普通玩家上传 ROM。默认关闭；OP2 不受此限制。")
            .define("allowPlayerRomUploads", false);
    private static final ModConfigSpec.BooleanValue COVER_UPLOADS = B.comment("允许有游戏库访问权的普通玩家上传 PNG 封面。与 ROM 上传独立，默认关闭。")
            .define("allowPlayerCoverUploads", false);
    private static final ModConfigSpec.BooleanValue SERVER_ROM_USE = B.comment("授权玩家可选服务器已有ROM配置自己的卡带或空闲街机；兼容旧名单权限，仍须allowAllPlayers或名单准入。")
            .define("allowServerRomUse", true);
    private static final ModConfigSpec.BooleanValue SERVER_COVER_USE = B.comment("授权玩家可选择服务器已有PNG封面；不授予上传权限。默认关闭。")
            .define("allowServerCoverUse", false);
    private static final ModConfigSpec.ConfigValue<List<String>> PLAYERS = B.define("authorizedPlayers", List.<String>of(), PlayerContentAccess::validPlayers);
    public static final ModConfigSpec SPEC = B.build();
    private PlayerContentAccess() {}
    public static void register() {
        net.neoforged.fml.ModLoadingContext.get().getActiveContainer().registerConfig(
                net.neoforged.fml.config.ModConfig.Type.SERVER, SPEC, "game-console-content-server.toml");
    }
    private static boolean validPlayers(Object value) {
        if (!(value instanceof List<?> players) || players.size() > PlayerContentPolicy.MAX_PLAYERS) return false;
        try {
            Set<UUID> ids = new HashSet<>();
            for (Object player : players) if (!(player instanceof String name) || !ids.add(PlayerContentPolicy.parsePlayer(name))) return false;
            return true;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    public static PlayerContentPolicy policy() {
        if (!SPEC.isLoaded()) return new PlayerContentPolicy(false, false, false, Set.of());
        Set<UUID> ids = new HashSet<>();
        for (String value : PLAYERS.get()) ids.add(PlayerContentPolicy.parsePlayer(value));
        return new PlayerContentPolicy(ALL_PLAYERS.get(), ROM_UPLOADS.get(), COVER_UPLOADS.get(), ids,
                SERVER_ROM_USE.get(), SERVER_COVER_USE.get());
    }
    public record Options(boolean allPlayers, boolean serverRomUse, boolean serverCoverUse,
                          boolean romUploads, boolean coverUploads) {}
    public static Options options() { return options(policy()); }
    private static Options options(PlayerContentPolicy p) {
        return new Options(p.allPlayers(), p.serverRomUse(), p.serverCoverUse(), p.romUploads(), p.coverUploads());
    }
    /** GUI transaction: no caller-supplied UUID list; preserve the full authoritative list. */
    public static boolean updateOptions(ServerPlayer player, Options expected, Options next) {
        if (!currentOperator(player) || expected == null || next == null) return false;
        var server = player.getServer(); var connection = player.connection.getConnection(); var level = player.serverLevel();
        PlayerContentPolicy old = policy();
        if (!options(old).equals(expected)) return false;
        PlayerContentPolicy changed = new PlayerContentPolicy(next.allPlayers(), next.romUploads(), next.coverUploads(),
                old.allowedPlayers(), next.serverRomUse(), next.serverCoverUse());
        return currentOperator(player) && player.getServer() == server && player.connection.getConnection() == connection
                && player.serverLevel() == level && policy().equals(old) && save(changed);
    }
    private static boolean currentOperator(ServerPlayer player) {
        return player != null && player.getServer() != null && player.getServer().isSameThread()
                && player.hasPermissions(2) && player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                && player.connection != null && player.connection.getConnection().isConnected()
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }
    public static int capabilities(ServerPlayer player) {
        if (player == null || player.getServer() == null || !player.getServer().isSameThread()
                || !player.isAlive() || player.isSpectator() || player.hasDisconnected() || player.connection == null
                || !player.connection.getConnection().isConnected()
                || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return 0;
        return policy().capabilities(player.getUUID(), player.hasPermissions(2));
    }
    public static boolean canBrowse(ServerPlayer player) { return PlayerContentPolicy.has(capabilities(player), PlayerContentPolicy.BROWSE); }
    public static boolean canUseServerRom(ServerPlayer player) { return PlayerContentPolicy.has(capabilities(player), PlayerContentPolicy.SERVER_ROM_USE); }
    public static boolean canUseServerCover(ServerPlayer player) { return PlayerContentPolicy.has(capabilities(player), PlayerContentPolicy.SERVER_COVER_USE); }
    public static boolean canUploadRom(ServerPlayer player) { return PlayerContentPolicy.has(capabilities(player), PlayerContentPolicy.ROM_UPLOAD); }
    public static boolean canUploadCover(ServerPlayer player) { return PlayerContentPolicy.has(capabilities(player), PlayerContentPolicy.COVER_UPLOAD); }
    /** Console commands still pass authority/context, not a naked public write setter. */
    static boolean update(net.minecraft.commands.CommandSourceStack source, PlayerContentPolicy expected, PlayerContentPolicy next) {
        if (source == null || !source.hasPermission(2) || source.getServer() == null || !source.getServer().isSameThread()
                || expected == null || next == null || !policy().equals(expected)) return false;
        if (source.getEntity() instanceof ServerPlayer p && !currentOperator(p)) return false;
        return source.hasPermission(2) && source.getServer().isSameThread() && policy().equals(expected) && save(next);
    }
    private static boolean save(PlayerContentPolicy next) {
        if (!SPEC.isLoaded()) return false;
        PlayerContentPolicy old = policy();
        try { set(next); SPEC.save(); return true; }
        catch (RuntimeException failure) {
            set(old);
            cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Cannot save player cartridge permissions; restored in-memory settings", failure);
            return false;
        }
    }
    private static void set(PlayerContentPolicy value) {
        ALL_PLAYERS.set(value.allPlayers()); ROM_UPLOADS.set(value.romUploads()); COVER_UPLOADS.set(value.coverUploads());
        SERVER_ROM_USE.set(value.serverRomUse()); SERVER_COVER_USE.set(value.serverCoverUse());
        PLAYERS.set(value.allowedPlayers().stream().map(UUID::toString).sorted().toList());
    }
}
