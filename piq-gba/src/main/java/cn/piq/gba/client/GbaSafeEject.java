// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.gba.bridge.GbaSession;

/** Runs off the Minecraft thread. A closed core is not proof that its final save succeeded. */
public final class GbaSafeEject {
    public record Result(boolean safe,String failure) {}
    public static Result finish(GbaSession session) {
        if(session==null)return new Result(true,"");
        try {
            session.close();
            if(!session.awaitClosed(5000))return new Result(false,"核心退出/电池存档尚未确认，请稍后重试");
            String failure=session.error();
            return failure==null||failure.isBlank()?new Result(true,""):new Result(false,failure);
        } catch(RuntimeException|LinkageError failure) {
            return new Result(false,failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());
        }
    }
    private GbaSafeEject() {}
}
