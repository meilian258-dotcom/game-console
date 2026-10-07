"""Offline actual-MC API compile and bytecode regression for the two confirmation screens.

Does not instantiate Minecraft, render a frame, alter ModernUI configuration or build any core.
"""
from __future__ import annotations

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
JAVA = (java_home() / 'bin')
MC = (gradle_home() / 'caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
FC = WORKSPACE / '制作Mod/03-街机模拟/PIQ-FC街机/alpha18-device-ui-v2/piq_fc_arcade-0.31.0-alpha.18.jar'
SFC = WORKSPACE / '制作Mod/03-街机模拟/PIQ-SFC家用/0.1.0-alpha.5/piq_sfc_home-0.1.0-alpha.5.jar'
SOURCES = {
    'cn.piq.fcarcade.client.ui.DeviceConfirmScreen': ROOT / 'src/main/java/cn/piq/fcarcade/client/ui/DeviceConfirmScreen.java',
    'cn.piq.sfchome.client.SfcJoinScreen': WORKSPACE / 'piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcJoinScreen.java',
}

def require(value, message):
    if not value:
        raise AssertionError(message)

def run(args):
    result = subprocess.run([str(x) for x in args], capture_output=True, encoding='utf-8', errors='replace', timeout=60)
    require(result.returncode == 0, result.stdout + result.stderr)
    return result.stdout

def code(path, name):
    return run([JAVA / 'javap.exe', '-J-Dfile.encoding=UTF-8', '-J-Dstdout.encoding=UTF-8', '-J-Dstderr.encoding=UTF-8', '-p', '-c', '-classpath', path, name])

def methods(raw):
    result = {}; signature = None; lines = []
    for line in raw.splitlines() + ['}']:
        if re.match(r'^  (?:\S.*\(.*\).*;|static \{\};)\s*$', line) or line == '}':
            if signature is not None:
                body = re.sub(r'#\d+', '#CP', '\n'.join(lines).strip())
                result[signature] = re.sub(r'[ \t]+//', ' //', body)
            signature = line.strip() if line != '}' else None
            lines = []
        elif signature is not None:
            lines.append(line.rstrip())
    return result

def method(raw, token):
    found = [value for key, value in methods(raw).items() if token in key]
    require(len(found) == 1, 'Missing or ambiguous method: ' + token)
    return found[0]

def safe_order(raw):
    body = method(raw, ' render(')
    call = 'screens/Screen.render:'
    foreground = [body.find(label) for label in ('GuiGraphics.drawCenteredString:', 'GuiGraphics.drawString:') if label in body]
    return (body.count(call) == 1 and bool(foreground) and body.index(call) < min(foreground)
            and 'Method renderBackground:' not in body and 'DeviceUi.panel' not in body
            and not any(' renderBackground(' in signature for signature in methods(raw)))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if args.report:
        args.report.resolve().relative_to(WORKSPACE.resolve())
        require(not args.report.exists(), 'Do not overwrite a previous report')
    cache = (gradle_home() / 'caches/modules-2/files-2.1')
    dependencies = [p for p in cache.rglob('*.jar') if not any(s in p.name for s in ('-sources', '-javadoc', '-userdev'))]
    checked = {}; preserved = {}
    with tempfile.TemporaryDirectory(prefix='piq-confirmation-order-') as td:
        temp = Path(td); out = temp / 'classes'; out.mkdir(); empty = temp / 'empty'; empty.mkdir()
        frozen = temp / 'fc-baseline.jar'; shutil.copyfile(FC, frozen)
        cp = os.pathsep.join(map(str, [frozen, MC, *dependencies]))
        argfile = temp / 'compile.args'
        argfile.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        run([JAVA / 'javac.exe', '@' + str(argfile), '-encoding', 'UTF-8', '-proc:none', '-sourcepath', empty, '-d', out, *SOURCES.values()])
        require(sorted(p.relative_to(out).as_posix() for p in out.rglob('*.class')) == sorted(name.replace('.', '/') + '.class' for name in SOURCES), 'Unexpected production class compiled')
        screen = code(MC, 'net.minecraft.client.gui.screens.Screen')
        screen_render = method(screen, ' render(')
        require(screen_render.index('Method renderBackground:') < screen_render.index('Renderable.render:'), 'Actual MC screen order changed')
        vanilla = code(MC, 'net.minecraft.client.gui.screens.ConfirmScreen')
        require(method(vanilla, ' render(').index('screens/Screen.render:') < method(vanilla, ' render(').index('GuiGraphics.drawCenteredString:'), 'Vanilla confirmation order changed')
        for name, source in SOURCES.items():
            current = code(out, name); baseline = code(FC if 'fcarcade' in name else SFC, name)
            require(safe_order(current), 'Unsafe confirmation draw order: ' + name)
            require(not safe_order(baseline), 'Negative old-screen regression fixture unexpectedly passed')
            initialization = method(current, ' init(')
            require(initialization.count('Button.builder:') == 2 and initialization.count('Button$Builder.build:') == 2, 'Expected two vanilla gray buttons')
            require(not any(text in current for text in ('DeviceUi.prepare', 'CartridgeScreenCompat', 'BlurHandler', 'loadBlacklist', 'mBlur')), 'Confirmation mutates global UI compatibility')
            require(all(text in method(current, ' render(') for text in ('enableScissor', 'disableScissor', 'Field scroll:', 'Field maxScroll:')), 'Long message scrolling lost')
            tokens = (' answer(', ' onClose(', ' isPauseScreen(', ' mouseScrolled(', ' keyPressed(') if 'fcarcade' in name else (' choose(', ' expire(', ' onClose(', ' removed(', ' isPauseScreen(')
            for token in tokens:
                require(method(current, token) == method(baseline, token), 'Callback/cancel/expiry semantics changed: ' + name + token)
            require('screens/Screen.keyPressed:' in method(current, ' keyPressed('), 'Vanilla Escape delegation lost')
            checked[name] = {'foreground_after_background_and_widgets': True, 'vanilla_buttons': 2, 'old_bad_order_rejected': True, 'long_message_scroll': True, 'global_blur_configuration_untouched': True}
            preserved[name] = list(tokens)
    report = {'ok': True, 'checks': checked, 'exact_legacy_lifecycle_methods': preserved,
              'actual_mc_screen_order': 'background -> widgets', 'vanilla_confirm_order': 'super.render -> title/body',
              'source_sha256': {str(p.relative_to(WORKSPACE)): hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in SOURCES.values()},
              'minecraft_started': False, 'emulator_started': False,
              'scope': 'Actual current production sources compiled against cached real MC APIs; disassembled actual generated classes and frozen alpha18 negative fixtures. No rendered screenshot or in-game visual claim.'}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open('x', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=True))

if __name__ == '__main__':
    main()
