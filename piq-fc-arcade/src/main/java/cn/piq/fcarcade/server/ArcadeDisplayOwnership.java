package cn.piq.fcarcade.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BiPredicate;

/** Persistent cabinet identity, independent of a display's position or facing. */
final class ArcadeDisplayOwnership {
    static final String DISPLAY_TAG = "piq_fc_occupancy";
    private static final String OWNER_PREFIX = "piq_fc_owner_v1|";

    private ArcadeDisplayOwnership() {}

    record Owner(String dimension, int x, int y, int z) {
        String tag() {
            return OWNER_PREFIX + dimension + "|" + x + "," + y + "," + z;
        }

        String legacyTag() {
            String key = dimension + "|" + x + "," + y + "," + z;
            return DISPLAY_TAG + "_" + Integer.toUnsignedString(key.hashCode(), 36);
        }
    }

    static Optional<Owner> resolve(
            Set<String> tags, String dimension, double x, double y, double z
    ) {
        if (!tags.contains(DISPLAY_TAG)) return Optional.empty();
        List<String> explicit = tags.stream().filter(tag -> tag.startsWith(OWNER_PREFIX)).toList();
        if (!explicit.isEmpty()) {
            if (explicit.size() != 1) return Optional.empty();
            Owner owner = parse(explicit.getFirst());
            return owner != null && owner.dimension().equals(dimension)
                    && tags.contains(owner.legacyTag()) ? Optional.of(owner) : Optional.empty();
        }

        List<String> legacy = tags.stream().filter(tag -> tag.startsWith(DISPLAY_TAG + "_")).toList();
        if (legacy.size() != 1 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || Math.abs(x) > 30_000_000
                || Math.abs(z) > 30_000_000 || Math.abs(y) > 30_000_000) {
            return Optional.empty();
        }
        // All supported layouts are at most 8 x 8: their center is <= 4 blocks
        // sideways from the anchor, and occupancy text is <= 8.38 blocks above.
        // Recover old hash-only ownership once on load, never from the nearest
        // surviving cabinet. Ambiguous hashes and externally moved text are left alone.
        Owner match = null;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -9; dy <= 0; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Owner candidate = new Owner(dimension,
                            (int) Math.floor(x) + dx, (int) Math.floor(y) + dy,
                            (int) Math.floor(z) + dz);
                    if (!candidate.legacyTag().equals(legacy.getFirst())) continue;
                    if (match != null) return Optional.empty();
                    match = candidate;
                }
            }
        }
        return Optional.ofNullable(match);
    }

    private static Owner parse(String tag) {
        String[] fields = tag.substring(OWNER_PREFIX.length()).split("\\|", -1);
        if (fields.length != 2 || fields[0].isBlank()) return null;
        String[] coordinates = fields[1].split(",", -1);
        if (coordinates.length != 3) return null;
        try {
            return new Owner(fields[0], Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    enum AnchorState { UNLOADED, PRESENT, MISSING }

    /** Only loaded, positively owned entities are retained; unload is not deletion. */
    static final class LoadedDisplays<T> {
        private final Map<T, Owner> owners = new LinkedHashMap<>();

        Optional<Owner> track(T display, Set<String> tags, String dimension,
                              double x, double y, double z) {
            Optional<Owner> owner = resolve(tags, dimension, x, y, z);
            owners.remove(display);
            owner.ifPresent(value -> owners.put(display, value));
            return owner;
        }

        void forget(T display) {
            owners.remove(display);
        }

        List<T> ownedBy(Owner owner) {
            List<T> result = new ArrayList<>();
            owners.forEach((display, actual) -> {
                if (owner.equals(actual)) result.add(display);
            });
            return result;
        }

        void discardIf(BiPredicate<T,Owner> predicate,Consumer<T> discard) {
            for (Map.Entry<T,Owner> entry : List.copyOf(owners.entrySet())) {
                if (predicate.test(entry.getKey(),entry.getValue())) {
                    owners.remove(entry.getKey());
                    discard.accept(entry.getKey());
                }
            }
        }

        void reconcile(Function<Owner, AnchorState> anchorState, Consumer<T> discard) {
            // Discard synchronously emits an entity-leave callback, which may
            // forget the same entry. Iterate a snapshot rather than the live map.
            for (Map.Entry<T, Owner> entry : List.copyOf(owners.entrySet())) {
                if (anchorState.apply(entry.getValue()) == AnchorState.MISSING) {
                    owners.remove(entry.getKey());
                    discard.accept(entry.getKey());
                }
            }
        }
    }
}
