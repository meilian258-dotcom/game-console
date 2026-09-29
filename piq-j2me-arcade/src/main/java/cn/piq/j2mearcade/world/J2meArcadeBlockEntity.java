package cn.piq.j2mearcade.world;

import cn.piq.j2mearcade.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class J2meArcadeBlockEntity extends BlockEntity {
    private static final String SELECTED_JAR_TAG = "SelectedJar";
    private String selectedJar = "";

    public J2meArcadeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.J2ME_ARCADE.get(), pos, state);
    }

    public String selectedJar() {
        return selectedJar;
    }

    public void setSelectedJar(String fileName) {
        String normalized = normalizeFileName(fileName);
        if (normalized.equals(selectedJar)) {
            return;
        }
        selectedJar = normalized;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    public static String normalizeFileName(String fileName) {
        String value = fileName == null ? "" : fileName.strip();
        if (value.length() > 128
                || value.contains("/")
                || value.contains("\\")
                || value.contains("..")
                || (!value.isEmpty() && !value.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))) {
            throw new IllegalArgumentException("Invalid Java ME game file name");
        }
        return value;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        try {
            selectedJar = normalizeFileName(tag.getString(SELECTED_JAR_TAG));
        } catch (IllegalArgumentException ignored) {
            selectedJar = "";
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!selectedJar.isEmpty()) {
            tag.putString(SELECTED_JAR_TAG, selectedJar);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }
}
