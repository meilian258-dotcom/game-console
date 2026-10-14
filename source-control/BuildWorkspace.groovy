import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Development-only paths. Does not change process/global temporary settings or clean old files. */
class BuildWorkspace {
    static final String OPTION = '-PpiqBuildWorkDir=<absolute ASCII directory> or PIQ_BUILD_WORK_DIR'

    static File resolve(File checkout, String configured, String osName, String systemDrive) {
        boolean windows = osName.toLowerCase(Locale.ROOT).startsWith('windows')
        File base
        if (configured != null) {
            if (configured.trim().isEmpty()) throw new IllegalArgumentException("Empty build workspace; set ${OPTION}")
            base = new File(configured)
        } else if (windows) {
            Path volume = primaryCheckout(checkout).toPath().toAbsolutePath().normalize().root
            if (volume == null || volume.toString().startsWith('\\\\') ||
                    volume.toString().replace('\\', '').replace('/', '').equalsIgnoreCase(systemDrive ?: 'C:')) {
                throw new IllegalArgumentException("No non-system Git work drive; set ${OPTION}")
            }
            base = volume.resolve('game-console-build').toFile()
        } else {
            base = new File(checkout, '.build-work')
        }
        validateBase(base, checkout)
        return base.toPath().toAbsolutePath().normalize().toFile()
    }

    static File primaryCheckout(File checkout) {
        File marker = new File(checkout, '.git')
        if (!marker.isFile()) return checkout
        String pointer = smallText(marker)
        if (!pointer.startsWith('gitdir: ')) throw new IllegalArgumentException('Invalid Git worktree pointer')
        File git = resolveRelative(checkout, pointer.substring(8).trim())
        File commonMarker = new File(git, 'commondir')
        File common = commonMarker.isFile() ? resolveRelative(git, smallText(commonMarker).trim()) : git
        if (!new File(common, 'HEAD').isFile()) throw new IllegalArgumentException('Cannot resolve primary Git directory')
        return common.toPath().toAbsolutePath().normalize().parent.toFile()
    }

    private static String smallText(File file) {
        if (file.length() > 4096) throw new IllegalArgumentException('Oversized Git workspace pointer')
        return file.getText('UTF-8').trim()
    }

    private static File resolveRelative(File parent, String text) {
        File path = new File(text)
        return path.isAbsolute() ? path : new File(parent, text)
    }

    static File projectDirectory(File base, File checkout, String projectName) {
        if (!(projectName ==~ /[A-Za-z0-9][A-Za-z0-9_.-]{0,95}/))
            throw new IllegalArgumentException('Build project name is not a safe ASCII component')
        String identity = checkout.toPath().toAbsolutePath().normalize().toString()
        if (File.separatorChar == ('\\' as char)) identity = identity.toLowerCase(Locale.ROOT)
        String hash = MessageDigest.getInstance('SHA-256').digest(identity.getBytes('UTF-8')).encodeHex().toString()
        return new File(base, "checkout-${hash.substring(0, 20)}/${projectName}")
    }

    static File taskDirectory(File projectDirectory, String taskName) {
        if (!(taskName ==~ /[A-Za-z0-9][A-Za-z0-9_.-]{0,95}/))
            throw new IllegalArgumentException('Build task name is not a safe ASCII component')
        return new File(projectDirectory, "tmp/${taskName}")
    }

    static void validateBase(File base, File checkout) {
        String text = base.path
        if (!base.isAbsolute() || !text.chars().allMatch { it >= 32 && it < 127 } ||
                text.replace('\\', '/').split('/').any { it == '..' } || text.startsWith('\\\\'))
            throw new IllegalArgumentException("Build workspace must be an absolute local ASCII directory; set ${OPTION}")
        Path path = base.toPath().toAbsolutePath().normalize()
        if (path.parent == null || path == checkout.toPath().toAbsolutePath().normalize() ||
                path == new File(System.getProperty('user.home')).toPath().toAbsolutePath().normalize())
            throw new IllegalArgumentException('Build workspace must be a dedicated directory, not a drive/home/checkout root')
        validateExisting(path)
    }

    static void validateExisting(Path path) {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.parent) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther())
                throw new IllegalArgumentException('Build workspace contains a file or reparse point: ' + current)
            String real = current.toRealPath().toString()
            String lexical = current.toString()
            if (File.separatorChar == ('\\' as char) ? !real.equalsIgnoreCase(lexical) : real != lexical)
                throw new IllegalArgumentException('Build workspace resolves through a redirected path: ' + current)
        }
    }

    static void prepare(File directory) {
        Path path = directory.toPath().toAbsolutePath().normalize()
        validateExisting(path)
        Files.createDirectories(path)
        validateExisting(path)
    }
}
