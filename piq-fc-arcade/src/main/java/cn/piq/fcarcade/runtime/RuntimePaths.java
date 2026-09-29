package cn.piq.fcarcade.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Fail closed on links/reparse points and changed parent metadata. No recursive deletion. */
final class RuntimePaths {
    private final Path root;
    private final Map<Path, Object> parents = new LinkedHashMap<>();

    RuntimePaths(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        if (this.root.getParent() == null) throw new IOException("不能把磁盘根目录作为游戏目录");
        check(this.root, true, false);
    }

    Path root() { return root; }

    Path resolve(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("路径超出游戏目录");
        return path;
    }

    BasicFileAttributes check(Path path, boolean directory, boolean missingAllowed) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root) && !root.startsWith(path)) throw new IOException("路径超出游戏目录");
        Path part = path.getRoot();
        BasicFileAttributes result = null;
        for (Path segment : path) {
            part = part.resolve(segment);
            boolean last = part.equals(path);
            BasicFileAttributes a;
            try { a = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) {
                if (missingAllowed && part.startsWith(root) && !part.equals(root)) return null;
                throw missing;
            }
            if (a.isSymbolicLink() || a.isOther()) throw new IOException("拒绝链接或重解析路径：" + part);
            if (!last || directory) {
                if (!a.isDirectory()) throw new IOException("路径不是普通目录：" + part);
                // Includes Windows junctions even on providers reporting them as directories.
                if (!part.toRealPath().equals(part.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("拒绝重定向目录：" + part);
                Object identity = identity(a);
                Object prior = parents.putIfAbsent(part, identity);
                if (prior != null && !prior.equals(identity)) throw new IOException("目录在操作期间发生变化：" + part);
            } else if (!a.isRegularFile()) throw new IOException("路径不是普通文件：" + part);
            result = a;
        }
        return result;
    }

    void unchanged(Path path, BasicFileAttributes before) throws IOException {
        BasicFileAttributes after = check(path, false, false);
        if (!Objects.equals(identity(before), identity(after))
                || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("文件在校验期间发生变化：" + path);
    }

    // Windows JDK21 deliberately returns null fileKey. Creation-time + canonical-path
    // checks detect ordinary parent replacement, not a malicious same-privilege OS adversary.
    // Published executable rollback additionally uses a retained hardlink witness/isSameFile.
    static Object identity(BasicFileAttributes attributes) {
        return attributes.fileKey() != null ? attributes.fileKey() : attributes.creationTime();
    }
}
