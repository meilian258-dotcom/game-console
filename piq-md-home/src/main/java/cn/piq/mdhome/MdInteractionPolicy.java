// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

/** Decisions shared by server loans and client input; not a substitute for server authority. */
public final class MdInteractionPolicy {
    public static boolean inControllerRange(double squaredDistance){return Double.isFinite(squaredDistance)&&squaredDistance>=0&&squaredDistance<=36;}
    public static int emptyHand(boolean mainEmpty,boolean offEmpty){return mainEmpty?0:offEmpty?1:-1;}
    private MdInteractionPolicy(){}
}
