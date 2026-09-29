// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.gba.bridge.GbaSaveScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

/** One local choice per actual world/server and player. Only an explicit Start writes it. */
public final class GbaHandheldSelectionStore {
    private static final int MAX_BYTES=8192;
    private final Path file;
    public GbaHandheldSelectionStore(Path root,GbaSaveScope scope) {
        file=root.toAbsolutePath().normalize().resolve("handheld-selection-v1")
                .resolve(scope.contextHash()).resolve(scope.player()+".txt");
    }
    public Optional<Path> load() throws IOException {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
        LocalRomLibrary.validateFile(file);
        byte[] bytes;try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(MAX_BYTES+1);}
        if(bytes.length>MAX_BYTES)throw new IOException("掌机游戏选择文件过大");
        String value=new String(bytes,StandardCharsets.UTF_8);
        if(value.isBlank()||value.chars().anyMatch(Character::isISOControl))throw new IOException("掌机游戏选择文件无效");
        try{Path path=Path.of(value);if(!path.isAbsolute())throw new IllegalArgumentException();return Optional.of(path.normalize());}
        catch(RuntimeException invalid){throw new IOException("掌机游戏路径无效",invalid);}
    }
    public void remember(Path rom) throws IOException {
        if(rom==null||!rom.isAbsolute())throw new IOException("需要游戏的完整本机路径");
        byte[] bytes=rom.normalize().toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES||rom.toString().chars().anyMatch(Character::isISOControl))throw new IOException("游戏路径无效");
        LocalRomLibrary.prepare(file.getParent());
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))LocalRomLibrary.validateFile(file);
        Path temporary=Files.createTempFile(file.getParent(),"choice-",".tmp");
        try {
            Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS);
            LocalRomLibrary.prepare(file.getParent());LocalRomLibrary.validateFile(temporary);
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))LocalRomLibrary.validateFile(file);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {if(Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){LocalRomLibrary.validateFile(temporary);Files.delete(temporary);}}
    }
}
