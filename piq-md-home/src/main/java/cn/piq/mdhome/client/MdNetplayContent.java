// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import cn.piq.fcarcade.client.ContentCardClient;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.client.watch.NetplayWatchContent;
import cn.piq.mdhome.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;

/** Download-only shared content-card capabilities, used equally by remote seats and observers. */
final class MdNetplayContent {
    record Request(UUID token,CompletableFuture<byte[]> bytes) {
        byte[] load()throws Exception {var value=bytes.get(120,TimeUnit.SECONDS);MdRom.validate(value);return value;}
        void cancel(){ContentCardClient.cancelDownload(token);}
    }
    private MdNetplayContent(){}
    static void requireLocalPermission(){if(!cn.piq.fcarcade.client.JniNetplayConsent.allowed())throw new IllegalStateException("本机已停用 JNI Netplay 或平台不支持；Windows x64 可用 /gameconsole-jni-netplay 恢复");}
    static Request request(long wire,BlockPos pos,String hash,int size){
        var token=UUID.randomUUID();var result=ContentCardClient.expectDownload(MdMod.SYSTEM,token,pos,hash,size);
        MdPublicNetwork.send(new MdPublicNetwork.Download(wire,token,hash));return new Request(token,result);
    }
    static void install(){NetplayWatchContent.register(MdMod.SYSTEM,(grant,connection)->{
        if(!MdNetplayProfile.AVAILABLE||!MdMod.SYSTEM.equals(grant.backend()))throw new IllegalStateException(MdNetplayProfile.UNAVAILABLE);
        requireLocalPermission();
        var current=net.minecraft.client.Minecraft.getInstance().getConnection();
        if(current==null||current.getConnection()!=connection||!connection.isConnected())throw new IllegalStateException("MD 旁观连接已失效");
        var pending=request(grant.wire(),grant.watch().descriptor().origin().pos(),grant.romHash(),0);
        return new NetplayWatchContent.Preparation(()->new CabinetBackend.NetplayContent(MdNetplayProfile.profile(),pending.load(),Map.of()),pending::cancel,true);
    });}
}
