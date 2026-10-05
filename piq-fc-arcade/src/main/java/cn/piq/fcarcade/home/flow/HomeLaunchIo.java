// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.home.flow;

import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Bounded read-only launch preparation. Callbacks must still validate the launch generation.
 * Work must not capture world objects or publish writes. Cancellation discards results, not IO leases. */
public final class HomeLaunchIo {
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"PIQ-home-launch-read");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public static <T> void read(MinecraftServer server,Callable<T> work,Consumer<T> success,Consumer<String> failure){
        if(!server.isSameThread())throw new IllegalStateException("Launch authority thread required");
        try{IO.execute(()->{try{T value=work.call();server.execute(()->success.accept(value));}
            catch(Exception error){String message=error.getMessage()==null?"读取失败":error.getMessage();server.execute(()->failure.accept(message));}});}
        catch(RejectedExecutionException full){failure.accept("开局读取队列已满，请稍后重试");}
    }
    private HomeLaunchIo(){}
}
