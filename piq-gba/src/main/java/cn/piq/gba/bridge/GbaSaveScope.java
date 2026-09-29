// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Immutable client-local owner namespace; never a server-supplied filesystem path. */
public record GbaSaveScope(String contextHash,UUID player) {
    public GbaSaveScope {
        if(contextHash==null||!contextHash.matches("[A-F0-9]{64}"))throw new IllegalArgumentException("GBA context digest");
        Objects.requireNonNull(player,"GBA player identity");
    }
    /** Keep exact protocol/address/port and world identity; no DNS, defaults or cross-server fallback. */
    public static GbaSaveScope of(String context,UUID player){
        if(context==null||context.isBlank()||context.length()>2048||context.chars().anyMatch(Character::isISOControl)
                ||(!(context.startsWith("world:")&&context.length()>6)&&!(context.startsWith("server:")&&context.length()>7)))
            throw new IllegalArgumentException("GBA requires a current world/server context");
        try{
            byte[] bytes=("piq-gba-save-scope-v1\n"+context).getBytes(StandardCharsets.UTF_8);
            return new GbaSaveScope(HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),player);
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    /** No filesystem effects. GbaSaveStore validates every ancestor before any actual read/write. */
    public Path resolve(Path saves){return Objects.requireNonNull(saves).toAbsolutePath().normalize().resolve("scoped-v1").resolve(contextHash).resolve(player.toString());}
}
