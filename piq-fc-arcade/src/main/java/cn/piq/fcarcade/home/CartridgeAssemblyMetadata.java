package cn.piq.fcarcade.home;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail closed on metadata that cannot be preserved by the physical split, without copying it twice. */
public final class CartridgeAssemblyMetadata {
    public enum Kind { WHOLE, BOARD, SHELL }
    public record Change(boolean customData, boolean present) {}
    private static final String DATA_KEY="piq_fc_home_cartridge";
    private static final Map<String,Integer> WHOLE=Map.of("id",11,"rom",8,"cover",8,"title",8,"board_variant",3,"assembly_revision",4,"save_mode",3,"saved",1);
    private static final Map<String,Integer> BOARD=Map.of("id",11,"rom",8,"board_title",8,"board_variant",3,"assembly_revision",4,"save_mode",3,"saved",1);
    private static final Map<String,Integer> SHELL=Map.of("id",11,"cover",8,"assembly_revision",4);
    private CartridgeAssemblyMetadata() {}
    /** Only explicit patches are provided here; ordinary default item components are deliberately absent. */
    public static boolean supported(List<Change> patches, Set<String> customRootKeys, boolean cartridgeCompound,
                                    Map<String,Integer> cartridgeFields, Kind kind) {
        if (patches.stream().anyMatch(change -> !change.customData() || !change.present())) return false;
        if (customRootKeys.stream().anyMatch(key -> !DATA_KEY.equals(key))) return false;
        if (customRootKeys.contains(DATA_KEY) && !cartridgeCompound) return false;
        if (!customRootKeys.contains(DATA_KEY) && !cartridgeFields.isEmpty()) return false;
        Map<String,Integer> allowed=switch(kind) { case WHOLE -> WHOLE; case BOARD -> BOARD; case SHELL -> SHELL; };
        return cartridgeFields.entrySet().stream().allMatch(field -> field.getValue().equals(allowed.get(field.getKey())));
    }
}
