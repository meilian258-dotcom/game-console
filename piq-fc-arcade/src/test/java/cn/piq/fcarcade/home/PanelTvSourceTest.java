package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Static wiring guardrails only; not a replacement for in-game cancellation/protection acceptance. */
class PanelTvSourceTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/"+name+".java"));}
    @Test void tableAndWallUseClickedFaceAndShareCloneItemButNotBlockCodec()throws Exception {
        var block=source("PanelTvBlock");
        for(var required:new String[]{"if(face==Direction.DOWN)return null","face.getAxis().isHorizontal()","wall?face:context.getHorizontalDirection().getOpposite()","PanelTvStructure.block(","getCloneItemStack","PanelTvStructure.item(","Codec.BOOL.fieldOf(\"wide\")","Codec.BOOL.fieldOf(\"wall\")","return CODEC"})assertTrue(block.contains(required),required);
        var item=source("PanelTvBlockItem");assertTrue(item.contains("super.place(context)"));assertTrue(item.contains("PanelTvStructure.place(context, state)"));
        assertTrue(item.contains("DataComponents.BLOCK_ENTITY_DATA"));assertTrue(item.contains("DataComponents.BLOCK_STATE"));
    }
    @Test void everyVariantHasItsOwnSavedOwnershipAndNeverTouchesLegacyLedgers()throws Exception {
        var data=source("PanelTvAssemblyData");assertTrue(data.contains("\"piq_panel_tv_assemblies\""));
        for(var field:new String[]{"Wide","Wall"}){assertTrue(data.contains("entry.getBoolean(\""+field+"\")"));assertTrue(data.contains("entry.putBoolean(\""+field+"\""));}
        for(var type:new String[]{"PanelTvStructure","PanelTvAssemblyLedger","PanelTvAssemblyData","PanelTvBlock","PanelTvBlockItem","PanelTvPartBlock","PanelTvPartBlockEntity","PanelTvRemovalGate","PanelTvFootprint"}) {
            var code=source(type);assertFalse(code.contains("LargeLcdTv"),type);assertFalse(code.contains("WideLcdTv"),type);
        }
    }
    @Test void placementRechecksHooksAndRollbackTouchesOnlyExactLoadedCells()throws Exception {
        var code=source("PanelTvStructure");
        for(var required:new String[]{"placementPermission(context,pos)","!exactPlaced(level,placed)","player.getItemInHand(context.getHand())!=held","ItemStack.matches(held,original)","level.getBlockState(pos)!=before","level.getBlockEntity(pos) != null","level.hasChunkAt(cell.pos()) && level.getBlockState(cell.pos()) == cell.installed()","level.getBlockEntity(cell.pos()) == cell.entity()","data.ledger.abortPlacement(id)","data.ledger.awaitPlacementEvent(id)","level.restoringBlockSnapshots","data.ledger.cancelPlacement("})assertTrue(code.contains(required),required);
        int close=code.indexOf("data.ledger.close(actual.id()"),drop=code.indexOf("if (first && drop)");assertTrue(close>0&&drop>close);
    }
    @Test void reloadAndCleanupRequireIdentityLayoutAndNeverDeleteForeignReplacement()throws Exception {
        var code=source("PanelTvStructure");
        for(var required:new String[]{"wall(level.getBlockState(pos))==assembly.wall()","assembly.id().equals(actual.id())","actual.anchor().equals(anchor(assembly))","actual.part() == part","if(!validPart(state,part))return false","&&unchanged(level,observed)","if (!level.hasChunkAt(other)) continue","if (level.getBlockEntity(pos) == original)","if(pos.equals(alreadyRemoving)||!owns(level,pos,entry,cell.part()))","if(!tv.structureInstalled()&&(entry==null||!entry.closed()))return"})assertTrue(code.contains(required),required);
        assertFalse(code.contains("getChunk("));assertFalse(code.contains("addRegionTicket"));
        var part=source("PanelTvPartBlockEntity");assertTrue(part.contains("tag.putUUID(\"Owner\""));assertTrue(part.contains("tag.putLong(\"Anchor\""));assertTrue(part.contains("PanelTvStructure.reconcile(this)"));assertTrue(part.contains("onlyOpCanSetNbt() { return true; }"));
    }
    public static void main(String[] args)throws Exception {
        var instance=new PanelTvSourceTest();int count=0;
        for(var m:PanelTvSourceTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(instance);count++;}
        System.out.println("Panel source wiring tests passed: "+count);
    }
}
