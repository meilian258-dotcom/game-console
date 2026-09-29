// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.server;

import cn.piq.sfcarcade.world.SfcArcadeBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Server-owned vanilla label that is visible to every nearby player, including the owner. */
final class SfcOccupancyDisplay {
    private static final String DISPLAY_TAG = "piq_sfc_occupancy";

    private SfcOccupancyDisplay() {
    }

    static void refresh(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos,
            String playerName
    ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)
                || !(level.getBlockState(pos).getBlock() instanceof SfcArcadeBlock)) {
            return;
        }
        Vec3 labelPos = Vec3.atBottomCenterOf(pos).add(0.0D, 1.38D, 0.0D);
        String machineTag = machineTag(dimension, pos);
        List<TextDisplay> displays = find(level, labelPos, machineTag);
        TextDisplay display = displays.isEmpty()
                ? spawn(level, labelPos, machineTag)
                : displays.getFirst();
        for (int index = 1; index < displays.size(); index++) {
            displays.get(index).discard();
        }
        if (display != null) update(display, playerName);
    }

    static void remove(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos
    ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)) return;
        String machineTag = machineTag(dimension, pos);
        AABB area = AABB.ofSize(Vec3.atCenterOf(pos).add(0.0D, 1.0D, 0.0D),
                4.0D, 4.0D, 4.0D);
        level.getEntitiesOfClass(TextDisplay.class, area,
                        entity -> entity.getTags().contains(machineTag))
                .forEach(TextDisplay::discard);
    }

    private static TextDisplay spawn(
            ServerLevel level,
            Vec3 position,
            String machineTag
    ) {
        TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) return null;
        display.setPos(position.x, position.y, position.z);
        display.addTag(DISPLAY_TAG);
        display.addTag(machineTag);
        display.setNoGravity(true);
        level.addFreshEntity(display);
        return display;
    }

    private static void update(TextDisplay display, String playerName) {
        Component label = Component.literal("正在使用：")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(
                        playerName == null || playerName.isBlank() ? "玩家" : playerName)
                        .withStyle(ChatFormatting.WHITE));
        CompoundTag tag = display.saveWithoutId(new CompoundTag());
        tag.putString("text", Component.Serializer.toJson(label, display.registryAccess()));
        tag.putInt("line_width", 320);
        tag.putInt("background", 0xB0101824);
        tag.putByte("text_opacity", (byte) 255);
        tag.putBoolean("shadow", true);
        tag.putBoolean("see_through", false);
        tag.putBoolean("default_background", false);
        tag.putString("alignment", "center");
        tag.putString("billboard", "center");
        tag.putFloat("view_range", 2.0F);
        display.load(tag);
    }

    private static List<TextDisplay> find(
            ServerLevel level,
            Vec3 position,
            String machineTag
    ) {
        return level.getEntitiesOfClass(TextDisplay.class,
                AABB.ofSize(position, 2.0D, 2.0D, 2.0D),
                entity -> entity.getTags().contains(machineTag));
    }

    private static String machineTag(ResourceKey<Level> dimension, BlockPos pos) {
        String key = dimension.location() + "|"
                + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        return DISPLAY_TAG + "_" + Integer.toUnsignedString(key.hashCode(), 36);
    }
}
