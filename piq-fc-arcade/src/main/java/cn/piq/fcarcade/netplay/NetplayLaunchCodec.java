// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.netplay;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Shared wire shape for controller and read-only observer grants. */
public final class NetplayLaunchCodec {
    private NetplayLaunchCodec(){}
    public static final StreamCodec<RegistryFriendlyByteBuf,NetplayLaunchSpec> CODEC=StreamCodec.of((b,p)->{
        b.writeUtf(p.variant(),48);b.writeUtf(p.compatibilitySha(),64);b.writeBoolean(p.persistenceSupported());
    },b->new NetplayLaunchSpec(b.readUtf(48),b.readUtf(64),b.readBoolean()));
}
