package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeAssemblyMetadataTest {
    private static final String KEY="piq_fc_home_cartridge";
    private static final List<CartridgeAssemblyMetadata.Change> OWN=List.of(new CartridgeAssemblyMetadata.Change(true,true));
    private static final Map<String,Integer> FIELDS=Map.of("id",11,"rom",8,"cover",8,"title",8,"board_variant",3,"assembly_revision",4);
    private boolean supported(List<CartridgeAssemblyMetadata.Change> patches,Set<String> roots,boolean compound,
                              Map<String,Integer> fields,CartridgeAssemblyMetadata.Kind kind) {
        return CartridgeAssemblyMetadata.supported(patches,roots,compound,fields,kind);
    }
    @Test void defaultComponentsAreNotExplicitPatchesAndDoNotPreventNormalCards() {
        assertTrue(supported(List.of(),Set.of(),false,Map.of(),CartridgeAssemblyMetadata.Kind.WHOLE));
        assertTrue(supported(OWN,Set.of(KEY),true,FIELDS,CartridgeAssemblyMetadata.Kind.WHOLE));
    }
    @Test void internalCustomGameTitleIsARecognizedFieldNotAnUnsupportedDisplayComponent() {
        assertTrue(supported(OWN,Set.of(KEY),true,Map.of("id",11,"title",8),CartridgeAssemblyMetadata.Kind.WHOLE));
        assertTrue(supported(OWN,Set.of(KEY),true,Map.of("id",11,"rom",8,"board_title",8,"board_variant",3,"assembly_revision",4),CartridgeAssemblyMetadata.Kind.BOARD));
    }
    @Test void nameLoreAndThirdPartyExplicitAddsAreAllRejected() {
        for(String component:new String[]{"CUSTOM_NAME","LORE","third_party:value"}) {
            var patches=List.of(new CartridgeAssemblyMetadata.Change(true,true),new CartridgeAssemblyMetadata.Change(false,true));
            assertFalse(supported(patches,Set.of(KEY),true,FIELDS,CartridgeAssemblyMetadata.Kind.WHOLE),component);
        }
    }
    @Test void explicitRemovalOfDefaultsOrCustomDataIsRejected() {
        assertFalse(supported(List.of(new CartridgeAssemblyMetadata.Change(false,false)),Set.of(),false,Map.of(),CartridgeAssemblyMetadata.Kind.WHOLE));
        assertFalse(supported(List.of(new CartridgeAssemblyMetadata.Change(true,false)),Set.of(),false,Map.of(),CartridgeAssemblyMetadata.Kind.WHOLE));
    }
    @Test void thirdPartyRootCustomDataCannotBeLostOrDuplicated() {
        assertFalse(supported(OWN,Set.of(KEY,"third_party"),true,FIELDS,CartridgeAssemblyMetadata.Kind.WHOLE));
        assertFalse(supported(OWN,Set.of("other"),false,Map.of(),CartridgeAssemblyMetadata.Kind.WHOLE));
    }
    @Test void malformedOwnRootMustNotBeSilentlyReadAsEmptyCompound() {
        assertFalse(supported(OWN,Set.of(KEY),false,Map.of(),CartridgeAssemblyMetadata.Kind.WHOLE));
        assertFalse(supported(OWN,Set.of(),false,FIELDS,CartridgeAssemblyMetadata.Kind.WHOLE));
    }
    @Test void unrecognizedFieldsInsideOurNamespaceAreAlsoPreservedByRefusing() {
        assertFalse(supported(OWN,Set.of(KEY),true,Map.of("future_field",8),CartridgeAssemblyMetadata.Kind.WHOLE));
    }
    @Test void supportedNamesStillNeedCorrectNbtTypes() {
        for(var field:FIELDS.entrySet())
            assertFalse(supported(OWN,Set.of(KEY),true,Map.of(field.getKey(),field.getValue()+1),CartridgeAssemblyMetadata.Kind.WHOLE));
    }
    @Test void boardAndShellRejectCrossOwnedFields() {
        assertFalse(supported(OWN,Set.of(KEY),true,Map.of("cover",8),CartridgeAssemblyMetadata.Kind.BOARD));
        assertFalse(supported(OWN,Set.of(KEY),true,Map.of("rom",8),CartridgeAssemblyMetadata.Kind.SHELL));
        assertFalse(supported(OWN,Set.of(KEY),true,Map.of("title",8),CartridgeAssemblyMetadata.Kind.SHELL));
        assertTrue(supported(OWN,Set.of(KEY),true,Map.of("id",11,"cover",8),CartridgeAssemblyMetadata.Kind.SHELL));
    }
    @Test void rejectingEitherAssemblyInputIsReadOnly() {
        var fields=new java.util.HashMap<String,Integer>(FIELDS);fields.put("future_field",8);
        var before=Map.copyOf(fields);
        assertFalse(supported(OWN,Set.of(KEY),true,fields,CartridgeAssemblyMetadata.Kind.WHOLE));
        assertEquals(before,fields);
    }
}
