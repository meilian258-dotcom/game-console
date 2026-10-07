"""Local toolchain discovery for developer scripts; no downloads or execution."""
from pathlib import Path
import os
import shutil


def gradle_home():
    """Honor Gradle's explicit cache location, then its user-home default."""
    configured = os.environ.get('GRADLE_USER_HOME')
    return Path(configured).expanduser() if configured else Path.home() / '.gradle'


def java_home(configured=None):
    """Resolve a JDK from an argument, JAVA_HOME, or javac on PATH."""
    configured = configured or os.environ.get('JAVA_HOME')
    if configured:
        home = Path(configured).expanduser().resolve()
        if not any((home / 'bin' / name).is_file() for name in ('javac', 'javac.exe')):
            raise RuntimeError('The configured Java home must contain bin/javac (JDK 21 required).')
        return home
    compiler = shutil.which('javac')
    if compiler:
        return Path(compiler).resolve().parent.parent
    raise RuntimeError('JDK 21 not found. Set JAVA_HOME or put javac on PATH.')


def executable(name, environment_variable=None):
    """Resolve an optional developer executable without a maintainer-specific path."""
    configured = os.environ.get(environment_variable) if environment_variable else None
    candidate = shutil.which(configured or name)
    if candidate:
        return candidate
    hint = f' or set {environment_variable}' if environment_variable else ''
    raise RuntimeError(f'{name} not found. Put it on PATH{hint}.')
