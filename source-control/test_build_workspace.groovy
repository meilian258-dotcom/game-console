import java.nio.file.Files
import java.nio.file.Path

// Run with the Groovy runtime shipped in Gradle; this does not run Gradle or a game.
// groovy.ui.GroovyMain source-control/test_build_workspace.groovy <ASCII fixture parent>
if (args.length != 1) throw new IllegalArgumentException('Expected an absolute ASCII fixture parent')
def code = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def type = new GroovyClassLoader(getClass().classLoader).parseClass(new File(code, 'BuildWorkspace.groovy'))
def parent = new File(args[0])
type.validateBase(parent, new File('.'))
type.prepare(parent)
def fixture = Files.createTempDirectory(parent.toPath(), 'workspace-test-').toFile()
int checks = 0
def check = { boolean condition -> assert condition; checks++ }
def rejected = { Closure action ->
    boolean failed = false
    try { action() } catch (IllegalArgumentException expected) { failed = true }
    check(failed)
}
def checkout = new File(fixture, 'checkout'); checkout.mkdirs()
def configured = new File(fixture, 'explicit')
check(type.resolve(checkout, configured.path, 'Windows 11', 'C:') == configured)
check(!configured.exists()) // Resolution/configuration is read-only.
check(type.resolve(checkout, configured.path, 'Linux', null) == configured)
check(type.resolve(checkout, null, 'Linux', null) == new File(checkout, '.build-work'))
['', ' ', '.', 'relative', new File(fixture, 'non-ascii-中').path,
 new File(fixture, '../escape').path].each { bad -> rejected { type.resolve(checkout, bad, 'Windows 11', 'C:') } }
rejected { type.resolve(checkout, checkout.path, 'Linux', null) }
rejected { type.resolve(checkout, new File(System.getProperty('user.home')).path, 'Linux', null) }
rejected { type.resolve(checkout, checkout.toPath().root.toString(), 'Linux', null) }
def regular = new File(fixture, 'not-directory'); regular.text = 'fixture'
rejected { type.resolve(checkout, new File(regular, 'child').path, 'Linux', null) }
def first = type.projectDirectory(configured, checkout, 'piq-fc-arcade')
check(first.path.chars().allMatch { it < 128 })
check(first == type.projectDirectory(configured, checkout, 'piq-fc-arcade'))
check(first != type.projectDirectory(configured, new File(fixture, 'another-checkout'), 'piq-fc-arcade'))
check(first != type.projectDirectory(configured, checkout, 'piq-md-home'))
rejected { type.projectDirectory(configured, checkout, '../bad') }
rejected { type.projectDirectory(configured, checkout, 'nonascii-中') }
check(type.taskDirectory(first, 'test') != type.taskDirectory(first, 'packagedRuntimeSmoke'))
rejected { type.taskDirectory(first, '../escape') }
type.prepare(type.taskDirectory(first, 'test'))
check(type.taskDirectory(first, 'test').isDirectory())
type.prepare(type.taskDirectory(first, 'test')) // idempotent, no cleanup.
check(type.taskDirectory(first, 'test').isDirectory())

def primary = new File(fixture, 'primary'); new File(primary, '.git').mkdirs()
new File(primary, '.git/HEAD').text = 'ref: refs/heads/main\n'
def worktreeGit = new File(primary, '.git/worktrees/test'); worktreeGit.mkdirs()
new File(worktreeGit, 'commondir').text = '../..\n'
def worktree = new File(fixture, 'other-worktree'); worktree.mkdirs()
new File(worktree, '.git').text = 'gitdir: ' + worktreeGit.path + '\n'
check(type.primaryCheckout(worktree) == primary)
check(type.primaryCheckout(primary) == primary)
check(type.primaryCheckout(checkout) == checkout)
if (File.separatorChar == ('\\' as char)) {
    String volume = primary.toPath().root.toString().replace('\\', '')
    rejected { type.resolve(worktree, null, 'Windows 11', volume) }
    String system = volume.equalsIgnoreCase('C:') ? 'Z:' : 'C:'
    check(type.resolve(worktree, null, 'Windows 11', system) == primary.toPath().root.resolve('game-console-build').toFile())
}
new File(worktree, '.git').text = 'not a Git pointer'
rejected { type.primaryCheckout(worktree) }
new File(worktree, '.git').text = 'x' * 4097
rejected { type.primaryCheckout(worktree) }
boolean linkChecked = false
try {
    Path link = new File(fixture, 'redirect').toPath()
    Files.createSymbolicLink(link, checkout.toPath())
    rejected { type.resolve(checkout, link.resolve('child').toString(), 'Linux', null) }
    linkChecked = true
} catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
    // Windows without the symlink privilege: report the untested branch, do not pretend it passed.
}
println "BuildWorkspace: ${checks} checks passed; symlink rejection ${linkChecked ? 'verified' : 'not exercised (OS privilege)'}"
println 'Fixture retained under the explicitly supplied work directory.'
