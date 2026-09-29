package cn.piq.fcarcade.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Owns the fixed text-display entity attached to a wall leaderboard panel. */
public final class LeaderboardPanelDisplay {
    private static final String DISPLAY_TAG = "piq_fc_score_panel";
    private LeaderboardPanelDisplay() {
    }

    public static void refresh(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            boolean enabled,
            int scalePercent,
            int offsetXHundredths,
            int offsetYHundredths,
            int depthHundredths,
            List<ArcadeScoreStore.ScoreEntry> entries,
            int pageStart
    ) {
        if (!enabled) {
            remove(level, pos);
            return;
        }
        Vec3 position = position(
                pos,
                facing,
                offsetXHundredths,
                offsetYHundredths,
                depthHundredths);
        String panelTag = panelTag(level.dimension(), pos);
        List<TextDisplay> displays = find(level, pos, panelTag);
        TextDisplay display = displays.isEmpty()
                ? spawn(level, position, panelTag)
                : displays.getFirst();
        for (int index = 1; index < displays.size(); index++) {
            displays.get(index).discard();
        }
        if (display == null) return;
        display.setPos(position.x, position.y, position.z);
        update(
                display,
                ArcadeLeaderboardComponent.create(entries, pageStart),
                scalePercent / 100.0F,
                facing);
    }

    public static void remove(ServerLevel level, BlockPos pos) {
        String panelTag = panelTag(level.dimension(), pos);
        find(level, pos, panelTag).forEach(TextDisplay::discard);
    }

    static Vec3 position(
            BlockPos pos,
            Direction facing,
            int offsetXHundredths,
            int offsetYHundredths,
            int depthHundredths
    ) {
        Direction right = facing.getClockWise();
        double depth = LeaderboardPanelPlacement.outwardOffset(
                depthHundredths);
        double horizontal = offsetXHundredths / 100.0D;
        return Vec3.atCenterOf(pos)
                .add(facing.getStepX() * depth, 0.0D, facing.getStepZ() * depth)
                .add(right.getStepX() * horizontal,
                        offsetYHundredths / 100.0D,
                        right.getStepZ() * horizontal);
    }

    private static TextDisplay spawn(
            ServerLevel level,
            Vec3 position,
            String panelTag
    ) {
        TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) return null;
        display.setPos(position.x, position.y, position.z);
        display.addTag(DISPLAY_TAG);
        display.addTag(panelTag);
        display.setNoGravity(true);
        level.addFreshEntity(display);
        return display;
    }

    private static List<TextDisplay> find(
            ServerLevel level,
            BlockPos pos,
            String panelTag
    ) {
        AABB area = AABB.ofSize(Vec3.atCenterOf(pos), 8.0D, 8.0D, 8.0D);
        return level.getEntitiesOfClass(
                TextDisplay.class,
                area,
                entity -> entity.getTags().contains(DISPLAY_TAG)
                        && entity.getTags().contains(panelTag));
    }

    private static void update(
            TextDisplay display,
            Component text,
            float scale,
            Direction facing
    ) {
        CompoundTag tag = display.saveWithoutId(new CompoundTag());
        tag.putString("text", Component.Serializer.toJson(
                text,
                display.registryAccess()));
        tag.putInt("line_width", 260);
        tag.putInt("background", 0xD0081018);
        tag.putByte("text_opacity", (byte) 255);
        tag.putBoolean("shadow", true);
        tag.putBoolean("see_through", false);
        tag.putBoolean("default_background", false);
        tag.putString("alignment", "center");
        tag.putString("billboard", "fixed");
        tag.putFloat("view_range", 2.5F);
        tag.put("transformation", transformationTag(0.30F * scale));
        tag.put("Rotation", floatList(facing.toYRot(), 0.0F));
        display.load(tag);
    }

    private static CompoundTag transformationTag(float scale) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(0.0F, 0.0F, 0.0F));
        transformation.put(
                "left_rotation",
                floatList(0.0F, 0.0F, 0.0F, 1.0F));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put(
                "right_rotation",
                floatList(0.0F, 0.0F, 0.0F, 1.0F));
        return transformation;
    }

    private static ListTag floatList(float... values) {
        ListTag tag = new ListTag();
        for (float value : values) tag.add(FloatTag.valueOf(value));
        return tag;
    }

    private static String panelTag(
            ResourceKey<Level> dimension,
            BlockPos pos
    ) {
        int hash = 31 * dimension.location().hashCode() + pos.hashCode();
        return "piq_fc_score_panel_" + Integer.toUnsignedString(hash, 36);
    }
}
