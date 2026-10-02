package cn.piq.fcarcade.server;

import cn.piq.fcarcade.layout.ArcadeOccupancyLabelLayout;
import cn.piq.fcarcade.layout.ArcadeScreenBounds;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-owned cabinet status rendered through vanilla text-display entities. */
final class ArcadeOccupancyDisplay {
    private static final String OCCUPANCY_TAG = "piq_cabinet_occupancy_v1";
    private static final Map<MinecraftServer, ArcadeDisplayOwnership.LoadedDisplays<TextDisplay>>
            LOADED = new WeakHashMap<>();

    private ArcadeOccupancyDisplay() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(ArcadeOccupancyDisplay::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(ArcadeOccupancyDisplay::onEntityLeave);
        NeoForge.EVENT_BUS.addListener(ArcadeOccupancyDisplay::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        // Add-on cabinets do not depend on successfully initializing the FC ROM library.
        if (event.getServer().getTickCount() % 20 == 0) reconcile(event.getServer());
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof TextDisplay display) {
            // The event can precede FULL chunk promotion: only inspect tags and
            // coordinates here, never query blocks or force a neighboring chunk.
            loaded(level.getServer()).track(display, display.getTags(),
                    level.dimension().location().toString(), display.getX(), display.getY(), display.getZ())
                    .ifPresent(owner -> display.addTag(owner.tag()));
        }
    }

    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof TextDisplay display) {
            var tracked = LOADED.get(level.getServer());
            if (tracked != null) tracked.forget(display);
        }
    }

    static void stopped(MinecraftServer server) {
        LOADED.remove(server);
    }

    static void reconcile(MinecraftServer server) {
        var activeOwners = new java.util.HashSet<ArcadeDisplayOwnership.Owner>();
        for (var entry : cn.piq.fcarcade.cabinet.ServerCabinets.occupancy(server).entrySet()) {
            var target = entry.getKey();
            var dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    target.dimension());
            activeOwners.add(owner(dimension, target.anchor()));
            refresh(server, dimension, target.anchor(), entry.getValue());
        }
        // Reuse the existing TV entity/layout/ownership cleanup for opted-in home providers.
        // Only bounded active sessions are visited; never scan loaded consoles or all players.
        for (var entry : cn.piq.fcarcade.cabinet.WatchProviders.entries().entrySet()) {
            var provider = entry.getValue();
            try {
                for (var source : provider.sources(server).stream().limit(16).toList()) {
                    var descriptor = source.descriptor();
                    if (!entry.getKey().equals(descriptor.provider()) || !provider.isCurrent(server, source)) continue;
                    var dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, descriptor.dimension());
                    var level = server.getLevel(dimension);
                    if (level == null) continue;
                    var names = cn.piq.fcarcade.cabinet.WatchOccupancyNames.format(provider.occupancyPlayers(server, source), id -> {
                        var player = server.getPlayerList().getPlayer(id);
                        return player != null && !player.hasDisconnected() && player.serverLevel() == level
                                ? player.getGameProfile().getName() : null;
                    });
                    for (var screen : descriptor.screens().stream().limit(4).toList()) {
                        var link = cn.piq.fcarcade.home.HomeSystems.connectionForTv(level, screen.pos()).orElse(null);
                        if (link == null || !link.systemId().equals(entry.getKey())
                                || !link.console().getBlockPos().equals(descriptor.origin().pos())
                                || !link.consoleId().equals(descriptor.origin().identity())
                                || !link.televisionId().equals(screen.identity())
                                || !link.linkId().equals(descriptor.link())
                                || !cn.piq.fcarcade.home.HomePresentationSettings.occupancySupported(level, screen.pos())) continue;
                        if (!names.isBlank()) activeOwners.add(owner(dimension, screen.pos()));
                        refresh(server, dimension, screen.pos(), names);
                    }
                }
            } catch (RuntimeException | LinkageError unavailable) {
                // A stale/failed provider contributes no live owner; cleanup below removes its label.
            }
        }
        var tracked = LOADED.get(server);
        if (tracked == null) return;
        // This persistent tag also removes labels loaded after a restart/room closure.
        // Idle scoreboards are not tagged; live FC sessions retain their own label.
        tracked.discardIf((display, owner) -> display.getTags().contains(OCCUPANCY_TAG)
                && !activeOwners.contains(owner)
                && !ServerArcadeSessions.hasCabinetSession(server,
                        ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                                net.minecraft.resources.ResourceLocation.parse(owner.dimension())),
                        new BlockPos(owner.x(), owner.y(), owner.z())), TextDisplay::discard);
        tracked.reconcile(owner -> {
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.dimension().location().toString().equals(owner.dimension())) continue;
                BlockPos anchor = new BlockPos(owner.x(), owner.y(), owner.z());
                if (!level.hasChunkAt(anchor)) return ArcadeDisplayOwnership.AnchorState.UNLOADED;
                return level.getBlockState(anchor).getBlock() instanceof FcArcadeBlock
                        ? ArcadeDisplayOwnership.AnchorState.PRESENT
                        : ArcadeDisplayOwnership.AnchorState.MISSING;
            }
            return ArcadeDisplayOwnership.AnchorState.UNLOADED;
        }, TextDisplay::discard);
    }

    private static ArcadeDisplayOwnership.LoadedDisplays<TextDisplay> loaded(MinecraftServer server) {
        return LOADED.computeIfAbsent(server, ignored -> new ArcadeDisplayOwnership.LoadedDisplays<>());
    }

    static void refresh(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos anchor,
            String playerNames
    ) {
        if (playerNames == null || playerNames.isBlank()) {
            remove(server, dimension, anchor);
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(anchor)) return;
        ArcadeStructure structure = ArcadeStructure.resolve(level, anchor);
        if (!(level.getBlockState(structure.anchor()).getBlock()
                instanceof FcArcadeBlock arcadeBlock)) {
            remove(server, dimension, anchor);
            return;
        }
        if (!cn.piq.fcarcade.home.HomePresentationSettings.occupancyVisible(level, structure.anchor())) {
            // A TV preference hides only this owner's occupancy label; never a
            // neighboring cabinet, and it does not change any active lease.
            remove(server, dimension, structure.anchor());
            return;
        }

        Vec3 position = occupancyPosition(structure, arcadeBlock,
                cn.piq.fcarcade.home.HomeTvStructure.centered(level.getBlockState(structure.anchor())),
                level.getBlockEntity(structure.anchor()) instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual&&dual.compactFootprint());
        var owner = owner(dimension, structure.anchor());
        List<TextDisplay> displays = find(level, owner);
        TextDisplay display = displays.isEmpty() ? spawn(level, position, owner)
                : displays.getFirst();
        for (int index = 1; index < displays.size(); index++) {
            displays.get(index).discard();
        }
        if (display != null) {
            display.addTag(OCCUPANCY_TAG);
            display.setPos(position.x, position.y, position.z);
            updateOccupancy(display, playerNames, structure.facing());
        }
    }

    static void refreshLeaderboard(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos anchor,
            List<ArcadeScoreStore.ScoreEntry> entries,
            int windowStart
    ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(anchor)) return;
        ArcadeStructure structure = ArcadeStructure.resolve(level, anchor);
        if (!(level.getBlockState(structure.anchor()).getBlock()
                instanceof FcArcadeBlock arcadeBlock)) {
            remove(server, dimension, anchor);
            return;
        }

        Vec3 position = leaderboardPosition(structure, arcadeBlock,
                cn.piq.fcarcade.home.HomeTvStructure.centered(level.getBlockState(structure.anchor())),
                level.getBlockEntity(structure.anchor()) instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual&&dual.compactFootprint());
        var owner = owner(dimension, structure.anchor());
        List<TextDisplay> displays = find(level, owner);
        TextDisplay display = displays.isEmpty() ? spawn(level, position, owner)
                : displays.getFirst();
        for (int index = 1; index < displays.size(); index++) {
            displays.get(index).discard();
        }
        if (display != null) {
            display.removeTag(OCCUPANCY_TAG);
            display.setPos(position.x, position.y, position.z);
            updateLeaderboard(
                    display,
                    entries,
                    windowStart,
                    structure.facing(),
                    arcadeBlock.displayStyle());
        }
    }

    static void remove(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos anchor
    ) {
        var tracked = LOADED.get(server);
        if (tracked == null) return;
        // Ownership, not a position-sized box, also finds labels in an adjacent
        // loaded chunk while the machine's own chunk is unloaded.
        for (TextDisplay display : tracked.ownedBy(owner(dimension, anchor))) {
            tracked.forget(display);
            display.discard();
        }
    }

    private static TextDisplay spawn(
            ServerLevel level,
            Vec3 position,
            ArcadeDisplayOwnership.Owner owner
    ) {
        TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) return null;
        display.setPos(position.x, position.y, position.z);
        display.addTag(ArcadeDisplayOwnership.DISPLAY_TAG);
        display.addTag(owner.legacyTag());
        display.addTag(owner.tag());
        display.setNoGravity(true);
        return level.addFreshEntity(display) ? display : null;
    }

    private static void updateOccupancy(
            TextDisplay display,
            String playerNames,
            Direction facing
    ) {
        Component label = Component.literal("正在使用：")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(playerNames).withStyle(ChatFormatting.WHITE));
        updateCommon(display, label, 320, 0xB0101824, 1.0F, facing, 0.0F, "fixed");
    }

    private static void updateLeaderboard(
            TextDisplay display,
            List<ArcadeScoreStore.ScoreEntry> entries,
            int windowStart,
            Direction facing,
            ArcadeDisplayStyle style
    ) {
        boolean dual = style == ArcadeDisplayStyle.DUAL_CABINET;
        boolean rocket = style == ArcadeDisplayStyle.LEGACY_GENERIC || style == ArcadeDisplayStyle.PORTRAIT_CABINET || dual;
        updateCommon(
                display,
                ArcadeLeaderboardComponent.create(entries, windowStart),
                rocket ? RocketArcadeGeometry.LEADERBOARD_LINE_WIDTH : 240,
                0xD0081018,
                dual ? cn.piq.fcarcade.layout.DualCabinetGeometry.LEADERBOARD_SCALE
                        : rocket ? RocketArcadeGeometry.LEADERBOARD_SCALE
                        : cn.piq.fcarcade.home.UserTvLayout.supports(style) || style == ArcadeDisplayStyle.HOME_LCD_TV || style == ArcadeDisplayStyle.HOME_WIDE_LCD_TV || style == ArcadeDisplayStyle.HOME_LARGE_LCD_TV || style == ArcadeDisplayStyle.HOME_VINTAGE_TV ? .15F : 0.28F,
                facing,
                rocket ? (float) RocketArcadeGeometry.SCREEN_TILT_DEGREES : 0.0F,
                "fixed");
    }

    private static void updateCommon(
            TextDisplay display,
            Component text,
            int lineWidth,
            int background,
            float scale,
            Direction facing,
            float screenTilt,
            String billboard
    ) {
        CompoundTag tag = display.saveWithoutId(new CompoundTag());
        tag.putString("text", Component.Serializer.toJson(
                text,
                display.registryAccess()));
        tag.putInt("line_width", lineWidth);
        tag.putInt("background", background);
        tag.putByte("text_opacity", (byte) 255);
        tag.putBoolean("shadow", true);
        tag.putBoolean("see_through", false);
        tag.putBoolean("default_background", false);
        tag.putString("alignment", "center");
        tag.putString("billboard", billboard);
        tag.putFloat("view_range", 2.0F);
        tag.put("transformation", transformationTag(scale, screenTilt));
        tag.put("Rotation", floatList(facing.toYRot(), 0.0F));
        display.load(tag);
    }

    private static CompoundTag transformationTag(float scale, float screenTilt) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(0.0F, 0.0F, 0.0F));
        // Text local +Y must slope into the cabinet after the existing facing
        // yaw. Apply this locally, so all four directions share the same tilt.
        double halfTilt = Math.toRadians(-screenTilt) * 0.5D;
        transformation.put("left_rotation", floatList(
                (float) Math.sin(halfTilt), 0.0F, 0.0F, (float) Math.cos(halfTilt)));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("right_rotation", floatList(0.0F, 0.0F, 0.0F, 1.0F));
        return transformation;
    }

    private static ListTag floatList(float... values) {
        ListTag list = new ListTag();
        for (float value : values) list.add(FloatTag.valueOf(value));
        return list;
    }

    private static List<TextDisplay> find(
            ServerLevel level,
            ArcadeDisplayOwnership.Owner owner
    ) {
        return loaded(level.getServer()).ownedBy(owner).stream()
                .filter(display -> !display.isRemoved()).toList();
    }

    private static Vec3 occupancyPosition(
            ArcadeStructure structure,
            FcArcadeBlock arcadeBlock, boolean centered, boolean compactDual
    ) {
        if(cn.piq.fcarcade.home.UserTvLayout.supports(arcadeBlock.displayStyle())) {
            var p=cn.piq.fcarcade.home.UserTvLayout.screen(arcadeBlock.displayStyle(),RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(),structure.facing().getStepZ())).center();
            return new Vec3(structure.anchor().getX()+p.x(),structure.anchor().getY()+ArcadeOccupancyLabelLayout.labelY(arcadeBlock.displayStyle(),1),structure.anchor().getZ()+p.z());
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.HOME_LARGE_LCD_TV)
            return new Vec3(structure.anchor().getX()+.5,structure.anchor().getY()+
                    ArcadeOccupancyLabelLayout.labelY(arcadeBlock.displayStyle(),1),structure.anchor().getZ()+.5);
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.HOME_WIDE_LCD_TV) {
            var point = RocketArcadeGeometry.rotate(new RocketArcadeGeometry.Point(.75,
                    ArcadeOccupancyLabelLayout.labelY(arcadeBlock.displayStyle(), 1), .5),
                    RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(), structure.facing().getStepZ()));
            return new Vec3(structure.anchor().getX()+point.x(),structure.anchor().getY()+point.y(),structure.anchor().getZ()+point.z());
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.DUAL_CABINET) {
            var point = cn.piq.fcarcade.layout.DualCabinetGeometry.occupancy(
                    RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(), structure.facing().getStepZ()),compactDual);
            return new Vec3(structure.anchor().getX() + point.x(), structure.anchor().getY() + point.y(),
                    structure.anchor().getZ() + point.z());
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.HOME_RETRO_TV) {
            var center = ArcadeOccupancyLabelLayout.homeTvCenter(RocketArcadeGeometry.quarterTurns(
                    structure.facing().getStepX(), structure.facing().getStepZ()), centered);
            return new Vec3(structure.anchor().getX() + center.x(),
                    structure.anchor().getY() + ArcadeOccupancyLabelLayout.labelY(
                            arcadeBlock.displayStyle(), structure.height()),
                    structure.anchor().getZ() + center.z());
        }
        Direction screenRight = structure.facing().getCounterClockWise();
        double halfSpan = (structure.width() - 1) * 0.5D;
        double x = structure.anchor().getX() + 0.5D
                + screenRight.getStepX() * halfSpan;
        double z = structure.anchor().getZ() + 0.5D
                + screenRight.getStepZ() * halfSpan;
        double y = structure.anchor().getY()
                + ArcadeOccupancyLabelLayout.labelY(
                arcadeBlock.displayStyle(),
                structure.height());
        return new Vec3(x, y, z);
    }

    private static Vec3 leaderboardPosition(
            ArcadeStructure structure,
            FcArcadeBlock arcadeBlock, boolean centered, boolean compactDual
    ) {
        if(cn.piq.fcarcade.home.UserTvLayout.supports(arcadeBlock.displayStyle())) {
            var q=cn.piq.fcarcade.home.UserTvLayout.screen(arcadeBlock.displayStyle(),RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(),structure.facing().getStepZ()));
            var p=q.center();var n=q.normal();
            return new Vec3(structure.anchor().getX()+p.x()+n.x()*.008,structure.anchor().getY()+p.y(),structure.anchor().getZ()+p.z()+n.z()*.008);
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.HOME_VINTAGE_TV) {
            var screen = cn.piq.fcarcade.home.VintageTvLayout.screen(
                    RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(),structure.facing().getStepZ()));
            var p=screen.center();var n=screen.normal();
            return new Vec3(structure.anchor().getX()+p.x()+n.x()*.008,
                    structure.anchor().getY()+p.y(),structure.anchor().getZ()+p.z()+n.z()*.008);
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.HOME_LARGE_LCD_TV) {
            var screen = cn.piq.fcarcade.home.LargeLcdTvLayout.screen(
                    RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(),structure.facing().getStepZ()));
            var p=screen.center();var n=screen.normal();
            return new Vec3(structure.anchor().getX()+p.x()+n.x()*.008,
                    structure.anchor().getY()+p.y(),structure.anchor().getZ()+p.z()+n.z()*.008);
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.HOME_WIDE_LCD_TV) {
            var screen = cn.piq.fcarcade.home.WideLcdTvLayout.screen(
                    RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(), structure.facing().getStepZ()));
            var point = screen.center();var normal=screen.normal();
            return new Vec3(structure.anchor().getX()+point.x()+normal.x()*.008,
                    structure.anchor().getY()+point.y(),structure.anchor().getZ()+point.z()+normal.z()*.008);
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.DUAL_CABINET) {
            var point = cn.piq.fcarcade.layout.DualCabinetGeometry.leaderboardTextOrigin(
                    RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(), structure.facing().getStepZ()),compactDual);
            return new Vec3(structure.anchor().getX() + point.x(), structure.anchor().getY() + point.y(),
                    structure.anchor().getZ() + point.z());
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.PORTRAIT_CABINET) {
            var q = cn.piq.fcarcade.layout.PortraitCabinetGeometry.screen(RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(),structure.facing().getStepZ()));
            var p=q.center();var n=q.normal();
            return new Vec3(structure.anchor().getX()+p.x()+n.x()*.008,structure.anchor().getY()+p.y()+n.y()*.008,structure.anchor().getZ()+p.z()+n.z()*.008);
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.LEGACY_GENERIC) {
            var origin = RocketArcadeGeometry.leaderboardTextOrigin(
                    RocketArcadeGeometry.quarterTurns(structure.facing().getStepX(),
                            structure.facing().getStepZ()));
            return new Vec3(structure.anchor().getX() + origin.x(),
                    structure.anchor().getY() + origin.y(),
                    structure.anchor().getZ() + origin.z());
        }
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.HOME_RETRO_TV) {
            var center = ArcadeOccupancyLabelLayout.homeTvScreenCenter(RocketArcadeGeometry.quarterTurns(
                    structure.facing().getStepX(), structure.facing().getStepZ()), centered);
            var screen = ArcadeScreenBounds.resolve(1, 1, arcadeBlock.displayStyle());
            return new Vec3(structure.anchor().getX() + center.x(),
                    structure.anchor().getY() + (screen.bottom() + screen.top()) * 0.5,
                    structure.anchor().getZ() + center.z());
        }
        Direction screenRight = structure.facing().getCounterClockWise();
        double halfSpan = (structure.width() - 1) * 0.5D;
        double x = structure.anchor().getX() + 0.5D
                + screenRight.getStepX() * halfSpan;
        double z = structure.anchor().getZ() + 0.5D
                + screenRight.getStepZ() * halfSpan;
        ArcadeScreenBounds bounds = ArcadeScreenBounds.resolve(
                structure.width(), structure.height(), arcadeBlock.displayStyle());
        double y = structure.anchor().getY() + (bounds.bottom() + bounds.top()) * 0.5D;
        double outward = 0.5D - bounds.frontInset() + 0.01D;
        x += structure.facing().getStepX() * outward;
        z += structure.facing().getStepZ() * outward;
        return new Vec3(x, y, z);
    }

    private static ArcadeDisplayOwnership.Owner owner(
            ResourceKey<Level> dimension,
            BlockPos anchor
    ) {
        return new ArcadeDisplayOwnership.Owner(dimension.location().toString(),
                anchor.getX(), anchor.getY(), anchor.getZ());
    }
}
