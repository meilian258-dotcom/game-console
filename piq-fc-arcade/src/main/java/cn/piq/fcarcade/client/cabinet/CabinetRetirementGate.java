// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.cabinet;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Client-lifetime RA closing claims. Disconnects and UI closure cannot prove process cleanup. */
final class CabinetRetirementGate {
    private final Set<CompletableFuture<Void>> retiring=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean unknown;

    synchronized void retain(CompletableFuture<Void> completion){
        if(completion==null)unknown=true;
        else retiring.add(completion);
    }
    /** Never waits, cancels, obtrudes a result or clears a failed claim. */
    synchronized String reason(){
        retiring.removeIf(done->done.isDone()&&!done.isCompletedExceptionally()&&!done.isCancelled());
        if(unknown||retiring.stream().anyMatch(done->done.isCompletedExceptionally()||done.isCancelled()))
            return "上次 RetroArch 关闭未确认，暂不能重新开局；请保留诊断并重启客户端，不要强行替换运行会话";
        return retiring.isEmpty()?null:"RetroArch 正在关闭并回收子进程，请等待完成后重新开局";
    }
}
