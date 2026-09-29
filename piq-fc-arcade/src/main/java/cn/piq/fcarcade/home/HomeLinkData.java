package cn.piq.fcarcade.home;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension ledger; closed links remain only until both old ends acknowledge. */
final class HomeLinkData extends SavedData {
    final HomeLinkLedger ledger = new HomeLinkLedger();

    static HomeLinkData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(HomeLinkData::new, HomeLinkData::load), "piq_home_fc_links");
    }

    private static HomeLinkData load(CompoundTag tag, HolderLookup.Provider registries) {
        HomeLinkData data = new HomeLinkData();
        ListTag entries = tag.getList("Links", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            try {
                data.ledger.restore(new HomeLinkLedger.Link(entry.getUUID("Id"),
                        readEndpoint(entry.getCompound("Console"), HomeLinkLedger.Kind.CONSOLE),
                        readEndpoint(entry.getCompound("Tv"), HomeLinkLedger.Kind.TV),
                        entry.getBoolean("Closed"), entry.getBoolean("RefundClaimed"),
                        entry.getBoolean("ConsoleCleared"), entry.getBoolean("TvCleared")));
            } catch (IllegalArgumentException ignored) {
                // Corrupt/foreign ownership never authorizes a cable refund.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (var link : ledger.snapshots()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", link.id());
            entry.put("Console", writeEndpoint(link.console()));
            entry.put("Tv", writeEndpoint(link.tv()));
            entry.putBoolean("Closed", link.closed());
            entry.putBoolean("RefundClaimed", link.refundClaimed());
            entry.putBoolean("ConsoleCleared", link.consoleCleared());
            entry.putBoolean("TvCleared", link.tvCleared());
            entries.add(entry);
        }
        tag.put("Links", entries);
        return tag;
    }

    private static CompoundTag writeEndpoint(HomeLinkLedger.Endpoint endpoint) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", endpoint.id());
        tag.putString("Dimension", endpoint.dimension());
        tag.putInt("X", endpoint.x()); tag.putInt("Y", endpoint.y()); tag.putInt("Z", endpoint.z());
        return tag;
    }

    private static HomeLinkLedger.Endpoint readEndpoint(CompoundTag tag, HomeLinkLedger.Kind kind) {
        if (!tag.hasUUID("Id") || tag.getString("Dimension").isBlank())
            throw new IllegalArgumentException("Missing hardware identity");
        return new HomeLinkLedger.Endpoint(tag.getUUID("Id"), tag.getString("Dimension"),
                tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"), kind);
    }
}
