"""Enable this repository's guard; never configure global Git or infer identity."""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--name'); p.add_argument('--email')
    args = p.parse_args()
    if sys.version_info < (3, 11): p.error('Python 3.11+ required')
    if bool(args.name) != bool(args.email): p.error('Provide both --name and --email, or neither')
    def git(*a, check=True):
        return subprocess.run(['git', '-C', str(ROOT), *a], check=check,
                              capture_output=True, text=True, encoding='utf8')
    top = Path(git('rev-parse', '--show-toplevel').stdout.strip()).resolve()
    if top != ROOT: p.error('Run from a clone of this repository, not a nested unrelated project')
    hooks = git('config', '--get', 'core.hooksPath', check=False).stdout.strip()
    if hooks and hooks != 'source-control/hooks':
        p.error('An existing hooksPath is configured; review how to preserve it before setup')
    for key, value in [('core.hooksPath', 'source-control/hooks'), ('piq.python', sys.executable),
                       ('core.autocrlf', 'false'), ('core.quotepath', 'false')]:
        git('config', '--local', key, value)
    if args.name:
        git('config', '--local', 'user.name', args.name)
        git('config', '--local', 'user.email', args.email)
    print('Repository-local guard enabled. No global config, download or commit performed.')
    if not git('var', 'GIT_AUTHOR_IDENT', check=False).returncode == 0:
        print('Commit identity is still missing; run setup with your own --name and --email.')


if __name__ == '__main__': main()
