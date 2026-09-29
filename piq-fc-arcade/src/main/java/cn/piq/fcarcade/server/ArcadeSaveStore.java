package cn.piq.fcarcade.server;

import cn.piq.fcarcade.ArcadeSnapshotUploadPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class ArcadeSaveStore {
    private static final int MAGIC = 0x50465153;
    private static final int LEGACY_VERSION = 1;
    private static final int OWNER_VERSION = 2;
    private static final int VERSION = 3;
    static final int MAX_SLOT_NAME_CHARS = 32;

    private final Path root;
    private final PersonalSaveActivity activity;

    ArcadeSaveStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.activity=new PersonalSaveActivity(this.root);
    }
    void played(java.util.UUID player,Instant now){try{activity.played(player,now);}catch(IOException error){throw new IllegalStateException("记录游玩时间失败",error);}}

    byte[] load(String machineKey, String romSha256) {
        return load(machineKey,romSha256,true);
    }
    /** Compatibility inspection must not touch access time or quarantine the source file. */
    byte[] loadReadOnly(String machineKey,String romSha256){return load(machineKey,romSha256,false);}
    private byte[] load(String machineKey,String romSha256,boolean maintain) {
        Path path = path(machineKey, romSha256);
        if (!Files.isRegularFile(path)) return null;
        try {
            byte[] file = Files.readAllBytes(path);
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(file))) {
                int magic = input.readInt();
                int version = input.readInt();
                if (magic != MAGIC
                        || (version != LEGACY_VERSION
                        && version != OWNER_VERSION
                        && version != VERSION)) {
                    throw new IOException("存档格式不兼容");
                }
                if (version >= OWNER_VERSION
                        && !machineKey.equals(input.readUTF())) {
                    throw new IOException("存档槽标识不匹配");
                }
                String storedRom = input.readUTF();
                if (!romSha256.equals(storedRom)) {
                    throw new IOException("存档 ROM 哈希不匹配");
                }
                if (version == VERSION) {
                    input.readUTF();
                    int players = input.readUnsignedByte();
                    if (players < 1 || players > 2) {
                        throw new IOException("存档玩家数量无效");
                    }
                }
                int stateBytes = input.readInt();
                if (stateBytes <= 0
                        || stateBytes > ArcadeSnapshotUploadPayload.MAX_STATE_BYTES) {
                    throw new IOException("存档状态长度无效");
                }
                byte[] expectedDigest = input.readNBytes(32);
                byte[] state = input.readNBytes(stateBytes);
                if (expectedDigest.length != 32
                        || state.length != stateBytes
                        || input.read() != -1
                        || !MessageDigest.isEqual(expectedDigest, sha256(state))) {
                    throw new IOException("存档数据损坏");
                }
                if(maintain)try {
                    Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
                } catch (IOException ignored) {
                    // A valid save must remain usable even if its access timestamp
                    // cannot be refreshed on the current filesystem.
                }
                return state;
            }
        } catch (IOException | RuntimeException error) {
            if(maintain)quarantine(path);
            return null;
        }
    }

    boolean exists(String saveKey, String romSha256) {
        return Files.isRegularFile(path(saveKey, romSha256));
    }

    void delete(String saveKey, String romSha256) {
        try {
            Files.deleteIfExists(path(saveKey, romSha256));
        } catch (IOException error) {
            throw new IllegalStateException("删除 FC 街机存档失败", error);
        }
    }

    boolean deleteByStorageId(String storageId) {
        if (storageId == null || !storageId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("存档文件标识无效");
        }
        try {
            return Files.deleteIfExists(root.resolve(storageId + ".sav"));
        } catch (IOException error) {
            throw new IllegalStateException("删除 FC 街机存档失败", error);
        }
    }

    boolean migrate(
            String oldSaveKey,
            String newSaveKey,
            String romSha256,
            String slotName,
            int players
    ) {
        if (!exists(oldSaveKey, romSha256)
                || exists(newSaveKey, romSha256)) {
            return false;
        }
        byte[] state = load(oldSaveKey, romSha256);
        if (state == null) return false;
        save(newSaveKey, romSha256, state, slotName, players);
        delete(oldSaveKey, romSha256);
        return true;
    }

    int cleanupOlderThanDays(int retentionDays, Instant now) {
        return cleanupOlderThanDays(retentionDays,now,info->false);
    }
    int cleanupOlderThanDays(int retentionDays, Instant now,java.util.function.Predicate<SaveInfo> active) {
        if (retentionDays < 0) {
            throw new IllegalArgumentException("存档保留天数不能为负数");
        }
        if (retentionDays == 0 || !Files.isDirectory(root)) return 0;
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        int deleted = 0;
        try {
            var manager=new FcSaveManagementStore(root);
            var rows=list();
            java.util.Set<String> activeOwners=new java.util.HashSet<>();
            for(var row:rows)if(active.test(row))activeOwners.add(FcSaveManagementStore.owner(row.saveKey()));
            for(var row:rows){
                String owner=FcSaveManagementStore.owner(row.saveKey());
                if(owner.isEmpty()||activeOwners.contains(owner)||active.test(row))continue;
                if(activity.lastPlayed(java.util.UUID.fromString(owner),now)>=cutoff.toEpochMilli())continue;
                var current=manager.current(row.storageId());
                if(current.format()!=3||!current.key().equals(row.saveKey())||active.test(row))continue;
                manager.delete(current.id(),current.version()); // recoverable, identity-checked exact-file move
                if(++deleted>=64)break;
            }
            return deleted;
        } catch (IOException error) {
            throw new IllegalStateException("清理过期 FC 个人存档失败", error);
        }
    }

    List<SaveInfo> list() {
        if (!Files.isDirectory(root)) return List.of();
        List<SaveInfo> result = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path candidate : paths.toList()) {
                if (!candidate.getFileName().toString()
                        .matches("[0-9a-f]{64}\\.sav")
                        || !Files.isRegularFile(
                        candidate,
                        LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (DataInputStream input = new DataInputStream(
                        Files.newInputStream(candidate))) {
                    if (input.readInt() != MAGIC) continue;
                    int version = input.readInt();
                    String saveKey;
                    boolean legacy;
                    String slotName = "";
                    int players = 1;
                    if (version == LEGACY_VERSION) {
                        saveKey = "";
                        legacy = true;
                    } else if (version == OWNER_VERSION
                            || version == VERSION) {
                        saveKey = input.readUTF();
                        legacy = false;
                    } else {
                        continue;
                    }
                    String romSha256 = input.readUTF();
                    if (!romSha256.matches("[0-9a-f]{64}")) continue;
                    if (version == VERSION) {
                        slotName = input.readUTF();
                        players = input.readUnsignedByte();
                        if (slotName.length() > MAX_SLOT_NAME_CHARS
                                || players < 1 || players > 2) {
                            continue;
                        }
                    }
                    String fileName = candidate.getFileName().toString();
                    result.add(new SaveInfo(
                            fileName.substring(0, fileName.length() - 4),
                            saveKey,
                            romSha256,
                            slotName,
                            players,
                            Files.getLastModifiedTime(candidate).toMillis(),
                            Files.size(candidate),
                            legacy));
                } catch (IOException | RuntimeException ignored) {
                    // The normal load path quarantines damaged saves. The
                    // read-only admin catalog only omits unreadable entries.
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("读取 FC 街机存档目录失败", error);
        }
        result.sort(Comparator.comparingLong(
                SaveInfo::modifiedEpochMillis).reversed());
        return List.copyOf(result);
    }

    void save(String machineKey, String romSha256, byte[] state) {
        save(machineKey, romSha256, state, "", 1);
    }

    void save(
            String machineKey,
            String romSha256,
            byte[] state,
            String slotName,
            int players
    ) {
        if (state == null || state.length == 0
                || state.length > ArcadeSnapshotUploadPayload.MAX_STATE_BYTES) {
            throw new IllegalArgumentException("存档状态大小无效");
        }
        slotName = normalizeSlotName(slotName);
        if (players < 1 || players > 2) {
            throw new IllegalArgumentException("存档玩家数量无效");
        }
        Path destination = path(machineKey, romSha256);
        Path temporary = null;
        boolean netplay=machineKey.startsWith("core|nes-netplay-");
        try {
            if(netplay)cn.piq.fcarcade.netplay.NetplaySaveStore.safeDirectory(root);
            Files.createDirectories(root);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(machineKey);
                output.writeUTF(romSha256);
                output.writeUTF(slotName);
                output.writeByte(players);
                output.writeInt(state.length);
                output.write(sha256(state));
                output.write(state);
            }
            temporary = Files.createTempFile(root, ".piq-save-", ".tmp");
            if(netplay){
                cn.piq.fcarcade.netplay.NetplaySaveStore.safeDirectory(root);
                cn.piq.fcarcade.netplay.NetplaySaveState.decode(state,null);
                byte[] old=loadReadOnly(machineKey,romSha256);
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){
                    if(old==null||Files.isSymbolicLink(destination)||!Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("旧 Netplay 存档无效，未覆盖");
                    cn.piq.fcarcade.netplay.NetplaySaveState.decode(old,null);
                    Path backup=destination.resolveSibling(destination.getFileName()+".previous"),staged=Files.createTempFile(root,".piq-backup-",".tmp");
                    try{Files.copy(destination,staged,StandardCopyOption.REPLACE_EXISTING);Files.move(staged,backup,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(staged);}
                }
            }
            try(var channel=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE)){
                var buffer=java.nio.ByteBuffer.wrap(bytes.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                if(netplay)throw ignored;
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("保存 FC 街机存档失败", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    Path path(String machineKey, String romSha256) {
        if (machineKey == null || machineKey.isBlank()
                || romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("存档键无效");
        }
        String keyHash = HexFormat.of().formatHex(
                sha256((machineKey + "|" + romSha256)
                        .getBytes(StandardCharsets.UTF_8)));
        return root.resolve(keyHash + ".sav");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 不支持 SHA-256", error);
        }
    }

    static String normalizeSlotName(String slotName) {
        String normalized = slotName == null ? "" : slotName.strip();
        if (normalized.length() > MAX_SLOT_NAME_CHARS
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("存档名称无效");
        }
        return normalized;
    }

    private static void quarantine(Path path) {
        try {
            if (!Files.exists(path)) return;
            Files.move(
                    path,
                    path.resolveSibling(
                            path.getFileName() + ".corrupt-" + Instant.now().toEpochMilli()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    record SaveInfo(
            String storageId,
            String saveKey,
            String romSha256,
            String slotName,
            int players,
            long modifiedEpochMillis,
            long fileBytes,
            boolean legacy
    ) {
    }
}
