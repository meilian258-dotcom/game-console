// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.world;

import cn.piq.sfcarcade.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class SfcArcadeBlockEntity extends BlockEntity {
    private static final String ROM_HASH_TAG = "RomHash";
    private static final String ROM_NAME_TAG = "RomName";

    private String romHash = "";
    private String romName = "";

    public SfcArcadeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SFC_ARCADE.get(), pos, state);
    }

    public String romHash() {
        return romHash;
    }

    public String romName() {
        return romName;
    }

    public void setRom(String hash, String name) {
        String normalizedHash = hash == null ? "" : hash;
        String normalizedName = name == null ? "" : name.strip();
        if (!normalizedHash.isEmpty() && !normalizedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SFC ROM SHA-256");
        }
        if (normalizedName.length() > 128) {
            throw new IllegalArgumentException("SFC ROM name is too long");
        }
        if (normalizedHash.equals(romHash) && normalizedName.equals(romName)) return;
        romHash = normalizedHash;
        romName = normalizedName;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        String loadedHash = tag.getString(ROM_HASH_TAG);
        romHash = loadedHash.matches("[0-9a-f]{64}") ? loadedHash : "";
        romName = tag.getString(ROM_NAME_TAG);
        if (romName.length() > 128) romName = romName.substring(0, 128);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!romHash.isEmpty()) {
            tag.putString(ROM_HASH_TAG, romHash);
            tag.putString(ROM_NAME_TAG, romName);
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
