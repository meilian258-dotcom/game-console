package cn.piq.fcarcade.client.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source seams complement executable pure IO/selection tests without starting Minecraft. */
class CabinetGameClientReuseSourceTest {
    private String source() throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetSharedGames.java"));
    }
    @Test void preparationAndNegotiationPrecedeAnyUploadProgressOrPut() throws Exception {
        String source = source();
        int describe = source.indexOf("describe(t,file)"), cached = source.indexOf("CabinetGameClientPlan.cacheSelected"),
                open = source.indexOf("t.call(CabinetGameNetwork.OPEN,manifest"), plan = source.indexOf("new CabinetGameClientPlan(manifest,opened.missingMask())"),
                progress = source.indexOf("t.progress(0,true)"), put = source.indexOf("plan.transferMissing((index,entry)->upload(");
        assertTrue(describe >= 0 && cached > describe && open > cached && plan > open && progress > plan && put > progress);
        assertTrue(source.contains("t.total=plan.missingBytes()"));
        assertTrue(source.contains("服务器已复用全部游戏文件，正在验证机柜授权"));
        assertTrue(source.contains("CabinetGameClientPlan.verifySelected(files,manifest,t::check)"));
    }
    @Test void ordinaryReopenUsesServerManifestAndVerifiedWholeContentCache() throws Exception {
        String source = source();
        int open = source.indexOf("t.call(CabinetGameNetwork.OPEN,null"), inspect = source.indexOf("var plan=CabinetGameClientPlan.inspectCache", open),
                get = source.indexOf("plan.transferMissing((index,entry)->download(", inspect), verify = source.indexOf("CabinetGameClientPlan.inspectCache(directory,manifest,t::check).missingMask()!=0", get),
                end = source.indexOf("t.call(CabinetGameNetwork.END", verify);
        assertTrue(open >= 0 && inspect > open && get > inspect && verify > get && end > verify);
        assertTrue(source.contains("CabinetGameClientPlan.cacheDirectory(root,key.context(),manifest)"));
        assertFalse(source.contains("CabinetGameSelection.load("));
        assertTrue(source.contains("已复用本地全部游戏文件，正在验证机柜授权"));
        assertFalse(source.contains("t.progress(entry.size(),false)"));
    }
    @Test void everyBranchStillNeedsExactEndManifestBeforeResolvedGrant() throws Exception {
        String source = source();
        int end = source.indexOf("t.call(CabinetGameNetwork.END"), identity = source.indexOf("!manifest.equals(ended.manifest())", end),
                resolved = source.indexOf("RESOLVED.put(request.lease()", identity);
        assertTrue(end >= 0 && identity > end && resolved > identity);
        assertTrue(source.contains("synchronized(t){t.check();RESOLVED.put"));
        assertTrue(source.contains("if(!complete)t.abort();else t.closed.set(true);WORKER.release()"));
    }
    @Test void invalidMasksAreBoundToPendingExactUploadCommand() throws Exception {
        String source = source();
        assertTrue(source.contains("t.connection!=source||t.closed.get()"));
        assertTrue(source.contains("p.command.operation()==CabinetGameNetwork.OPEN&&p.command.manifest()!=null"));
        assertTrue(source.contains("p.command.manifest(),r.manifest(),r.missingMask()"));
        assertTrue(source.indexOf("CabinetGameClientPlan.validReply") < source.indexOf("p.result.complete(r);", source.indexOf("CabinetGameClientPlan.validReply")));
    }
    @Test void workerCancellationAndConnectionTargetSafetyArePreserved() throws Exception {
        String source = source();
        for (String seam : new String[]{"if(!WORKER.tryAcquire())", "Minecraft.getInstance().isSameThread()", "mc.getConnection().getConnection()!=connection", "!request.target().matches(mc.level)",
                "new CabinetGameNetwork.Command(id,Math.min(sequence,20000),CabinetGameNetwork.CANCEL,request.lease()", "t.id,t::check,additional->checkCacheQuota(root,additional)",
                "RESOLVED.computeIfPresent(request.lease(),(key,value)->value.connection==connection?null:value)", "ACTIVE.remove(t.id,t)"}) assertTrue(source.contains(seam), seam);
    }
}
