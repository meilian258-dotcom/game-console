// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/** Trusted local addon registration. Network grants can select it, never construct it. */
public record NetplayVariant(String id,String label,NetplayProfile template,boolean persistenceSupported) {
    public NetplayVariant {
        NetplayLaunchSpec.checkVariant(id);Objects.requireNonNull(template);
        if(label==null||label.isBlank()||label.length()>80||label.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Netplay variant label");
    }
    public NetplayProfile profile(String name){
        return new NetplayProfile(template.owner(),template.resource(),template.sha(),name,template.options(),
                template.device(),template.sampleRate(),template.maxRomBytes(),template.ports(),template.jni(),template.jniAspect());
    }
    public NetplayLaunchSpec launch(){return new NetplayLaunchSpec(id,fingerprint(template),persistenceSupported);}
    /** Content is authorized separately; runtime identity must not depend on the selected ZIP name. */
    private String fingerprint(NetplayProfile profile){
        var text=new StringBuilder("PIQ-cabinet-netplay-variant-v1\n").append(id).append('\n')
                .append(persistenceSupported).append('\n').append(profile.sha().toLowerCase(Locale.ROOT)).append('\n')
                .append(profile.resource()).append('\n').append(profile.device()).append(':').append(profile.ports())
                .append(':').append(profile.sampleRate()).append(':').append(profile.maxRomBytes()).append('\n').append(profile.config());
        if(profile.jni()!=null)text.append("\nJNI-generic-v1\n").append(profile.jni().name()).append('\n')
                .append(profile.jni().extension()).append('\n').append(profile.jni().fullPath()).append('\n')
                .append(profile.jni().devices()).append('\n').append(profile.jniAspect());
        else text.append("\nRetroArch\n").append(NetplayProcess.RETROARCH_PROTOCOL).append('\n').append(NetplayProcess.RETROARCH_SHA);
        return NetplaySaveState.hash(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    public void verify(NetplayLaunchSpec grant,NetplayProfile actual){
        if(!launch().equals(grant)||!fingerprint(Objects.requireNonNull(actual)).equals(grant.compatibilitySha()))
            throw new IllegalArgumentException("Netplay 后端或核心版本不匹配，请安装同批配套；不会自动切换后端");
    }
    public void requireSaveMode(int mode){
        if(mode<0||mode>2||!persistenceSupported&&mode!=0)
            throw new IllegalArgumentException("此 RetroArch Netplay 试验暂不支持保存；请明确选择不保存，旧 JNI 存档保留");
    }
}
