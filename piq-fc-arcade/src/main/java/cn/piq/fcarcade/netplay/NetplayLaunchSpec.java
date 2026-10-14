// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.util.Objects;

/** A bounded server-selected key and fingerprint, never an executable path or core declaration. */
public record NetplayLaunchSpec(String variant,String compatibilitySha,boolean persistenceSupported) {
    public static final String LEGACY="legacy";
    /** Only callers without a variant registry retain the historical contract. */
    public static final NetplayLaunchSpec UNSPECIFIED=new NetplayLaunchSpec(LEGACY,"",true);
    public NetplayLaunchSpec {
        checkVariant(variant);Objects.requireNonNull(compatibilitySha);
        if(!compatibilitySha.matches("[a-f0-9]{64}")
                &&!(variant.equals(LEGACY)&&compatibilitySha.isEmpty()&&persistenceSupported))
            throw new IllegalArgumentException("Netplay compatibility fingerprint");
    }
    public static void checkVariant(String value){
        if(value==null||!value.matches("[a-z][a-z0-9-]{0,47}"))throw new IllegalArgumentException("Netplay variant");
    }
}
