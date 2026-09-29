"""Real SfcPlayback + real WASM in two JVMs, with a controlled pipe/codec coordinator.

This is NOT a Minecraft server/socket integration test. Only Host platform effects
(main-thread callback dispatch, graphics, speaker output and backup destination)
are replaced. Never reads a commercial ROM or writes a game instance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import random
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
MC = Path('C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def run(command, cwd, timeout=180):
    result = subprocess.run(list(map(str, command)), cwd=cwd, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        raise AssertionError(result.stdout + '\n' + result.stderr)
    return result.stdout


class Worker:
    def __init__(self, command, cwd, name):
        self.name, self.events, self.logs, self.sequence = name, [], [], 0
        self.incoming = queue.Queue()
        self.process = subprocess.Popen(list(map(str, command)), cwd=cwd, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, encoding='utf-8', errors='replace', bufsize=1)

        def reader():
            for line in self.process.stdout:
                if line.startswith('PIQ_QA '):
                    self.incoming.put(json.loads(line[7:]))
                else:
                    self.logs.append(line.rstrip())
            self.incoming.put({'event': 'eof'})

        threading.Thread(target=reader, daemon=True).start()
        self.wait_event('hello', pump=False)

    def consume(self, timeout=10):
        try:
            event = self.incoming.get(timeout=timeout)
        except queue.Empty as error:
            raise AssertionError(f'{self.name}: pipe deadline; ' + '\n'.join(self.logs[-20:])) from error
        self.events.append(event)
        if event['event'] in ('failure', 'eof'):
            raise AssertionError(f'{self.name}: {event}; ' + '\n'.join(self.logs[-20:]))
        return event

    def command(self, op, **fields):
        self.sequence += 1
        self.process.stdin.write(json.dumps({'id': self.sequence, 'op': op, **fields}) + '\n')
        self.process.stdin.flush()
        until = time.monotonic() + 15
        while time.monotonic() < until:
            event = self.consume(max(.01, until - time.monotonic()))
            if event['event'] == 'ack' and event['id'] == self.sequence:
                return event
        raise AssertionError(f'{self.name}: no command acknowledgment')

    def wait_event(self, kind, predicate=lambda e: True, start=0, pump=True, timeout=15):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            found = next((e for e in self.events[start:] if e['event'] == kind and predicate(e)), None)
            if found:
                return found
            if pump:
                status = self.command('poll')
                if status['error']:
                    raise AssertionError(f'{self.name}: playback error: {status["error"]}')
                time.sleep(.01)
            else:
                self.consume(max(.01, until-time.monotonic()))
        raise AssertionError(f'{self.name}: waiting for {kind}; ' + '\n'.join(self.logs[-15:]))

    def frames(self, generation):
        return {e['frame']: e for e in self.events if e['event'] == 'frame' and e['generation'] == generation}

    def finish(self):
        if self.process.poll() is None:
            try:
                self.command('quit')
                self.process.wait(timeout=10)
            except Exception:
                self.process.kill()
                self.process.wait(timeout=5)


def exercise(p1, p2, appliance=False):
    assertions = 0

    def check(condition, message):
        nonlocal assertions
        assertions += 1
        if not condition:
            raise AssertionError(message)

    for port, worker in enumerate((p1, p2)):
        worker.command('start', **({'port': -1 if port == 0 else 0, 'execution_host': port == 0} if appliance else {'port': port}))
    ready1 = p1.wait_event('ready')
    ready2 = p2.wait_event('ready')
    check(ready1['hash'] == ready2['hash'], 'actual worker initial states match')
    check(abs(ready1['fps']-ready2['fps']) < .001 and 59 < ready1['fps'] < 61, 'both actual cores use NTSC rate')
    check(ready1['lease'] != ready2['lease'], 'two independent controller lease identities')
    if appliance:
        check(ready1['port'] == -1 and ready1['execution_host'], 'background host has no controller port')
        check(ready2['port'] == 0 and not ready2['execution_host'], 'remote P1 is not the runtime host')

    def send(worker, first, trace):
        for offset in range(0, len(trace), 4):
            batch = trace[offset:offset+4]
            worker.command('frames', frame=first+offset, p1=[v[0] for v in batch], p2=[v[1] for v in batch])

    def await_frame(worker, frame, generation=1):
        return worker.wait_event('frame', lambda e: e['frame'] == frame and e['generation'] == generation)

    send(p1, 0, [(0, 0)]*20)
    await_frame(p1, 20)
    p1.command('input_edges')
    edges = p1.wait_event('timeline')['masks']
    check(edges == [1, 0, 256, 0, 2, 0, 512, 0], 'actual timeline retains same-tick short taps')
    send(p1, 20, [(mask, 0) for mask in edges])
    await_frame(p1, 28)
    boundary = 28

    def join(frame, guest_generation):
        nonce = str(uuid.uuid4())
        start1, start2 = len(p1.events), len(p2.events)
        p1.command('capture', token=nonce, frame=frame)
        snapshot = p1.wait_event('snapshot', lambda e: e['token'] == nonce, start1)
        check(snapshot['frame'] == frame and snapshot['size'] > 100_000, 'snapshot uses requested real worker boundary')
        # Deliberate upload latency, while P1 is not given future input batches.
        time.sleep(.08)
        check(p1.command('poll')['observed'] == frame, 'snapshot transfer does not reset or advance P1')
        p2.command('restore', token=nonce, frame=frame, sha=snapshot['sha'], bytes=snapshot['bytes'])
        applied = p2.wait_event('applied', lambda e: e['token'] == nonce and e['generation'] == guest_generation, start2)
        check(applied['success'] and applied['sha'] == snapshot['sha'], 'P2 imported exact real WASM snapshot')
        p1.command('commit', token=nonce, frame=frame, sha=applied['sha'])
        return {'frame': frame, 'bytes': snapshot['size'], 'chunks': snapshot['chunks'], 'sha256': snapshot['sha']}

    first_join = join(boundary, 1)
    trace, color_cases = [], []
    for port in range(2):
        for bit in range(12):
            mask = 1 << bit
            trace.extend([(mask, 0) if port == 0 else (0, mask)]*4)
            color_cases.append((boundary+len(trace), port, bit))
            trace.extend([(0, 0)]*2)
    trace.extend(list(zip(edges, list(reversed(edges)))))
    rng = random.Random(91821)
    jitter = []
    for offset in range(0, len(trace), 4):
        batch = trace[offset:offset+4]
        send(p1, boundary+offset, batch)
        delay = rng.choice([0, .003, .008, .015, .035])
        jitter.append(round(delay*1000))
        time.sleep(delay)
        send(p2, boundary+offset, batch)
    end = boundary+len(trace)
    await_frame(p1, end)
    await_frame(p2, end)

    def compare(first, last, guest_generation):
        a, b = p1.frames(1), p2.frames(guest_generation)
        for frame in range(first+1, last+1):
            check(frame in a and frame in b, f'both workers produced frame {frame}')
            check(a[frame]['video'] == b[frame]['video'], f'frame {frame} pixel equality')
            check(a[frame]['audio'] == b[frame]['audio'] and a[frame]['pcm_length'] == b[frame]['pcm_length'], f'frame {frame} PCM equality')
            check(a[frame]['width'] == b[frame]['width'] and a[frame]['height'] == b[frame]['height'], f'frame {frame} resolution equality')
    compare(boundary, end, 1)
    for frame, port, bit in color_cases:
        r, g, b = p1.frames(1)[frame]['rgb']
        check((r > 200 and g < 20 and b < 20) if port == 0 else (g > 200 and r < 20 and b < 20),
              f'original ROM observed actual port {port+1} button bit {bit}')

    # Guest close executes the production worker shutdown and releases its real
    # static core lease, while the separate host JVM retains its core and frame.
    p2.command('close')
    closed2 = p2.wait_event('closed')
    check(closed2['backups'] == 0, 'P2 never replaces host recovery backup')
    send(p1, end, [(0, 0)]*20)
    end += 20
    await_frame(p1, end)
    check(p1.command('poll')['backups'] == 0, 'P1 still uses original uninterrupted worker')

    cancelled = str(uuid.uuid4())
    cancellation_start = len(p1.events)
    p1.command('capture', token=cancelled, frame=end+8, drain=False)
    p1.command('cancel', token=cancelled)
    send(p1, end, [(0, 0)]*8)
    end += 8
    await_frame(p1, end)
    p1.command('gate_timeout')
    check(not any(e['event'] == 'snapshot' and e.get('token') == cancelled for e in p1.events[cancellation_start:]), 'cancelled pending join never exports snapshot')

    old_lease = ready2['lease']
    p2.command('start', **({'port': 0, 'execution_host': False} if appliance else {'port': 1}))
    restarted = p2.wait_event('ready', lambda e: e['generation'] == 2)
    check(restarted['lease'] != old_lease, 'rejoin obtains new lease')
    check(restarted['hash'] == ready2['hash'], 'new guest cold bootstrap is deterministic')
    second_join = join(end, 2)
    rejoin_start = end
    follow = [(1 if i % 4 < 2 else 0, 256 if i % 5 < 2 else 0) for i in range(32)]
    send(p1, end, follow)
    time.sleep(.055)
    send(p2, end, follow)
    end += len(follow)
    await_frame(p1, end)
    await_frame(p2, end, 2)
    compare(rejoin_start, end, 2)

    # A deliberately missing frame must stop the guest locally, never run a
    # mismatched input sequence. This is not a server transport reordering test.
    p2.command('frames', frame=end+1, p1=[0], p2=[0])
    until = time.monotonic()+5
    guest_error = ''
    while time.monotonic() < until:
        guest_error = p2.command('poll')['error']
        if guest_error:
            break
        time.sleep(.01)
    check(bool(guest_error), 'out-of-order/gapped frame fails closed in real worker')
    send(p1, end, [(0, 0)]*8)
    end += 8
    await_frame(p1, end)
    check(not p1.command('poll')['error'], 'guest worker failure does not stop host worker')
    p2.command('close')

    # Hold the actual ready callback; close before pumping. Its production
    # running/isCurrent guards must suppress the stale callback.
    p2.command('start', drain=False, **({'port': 0, 'execution_host': False} if appliance else {'port': 1}))
    until = time.monotonic()+10
    while time.monotonic() < until:
        pending = p2.command('poll', drain=False)
        if pending['callbacks']:
            break
        time.sleep(.01)
    check(pending['callbacks'] > 0, 'ready callback really queued before close')
    p2.command('close')
    check(not any(e['event'] == 'ready' and e['generation'] == 3 for e in p2.events), 'close suppresses queued stale ready callback')
    p1.command('close')
    closed1 = p1.wait_event('closed')
    check(closed1['observed'] == end and closed1['backups'] == 1 and len(closed1['backup_sha']) == 64, 'host shutdown retains final progress for recovery')
    status1, status2 = p1.command('poll'), p2.command('poll')
    check(not status1['lease'] and not status2['lease'], 'both real core leases released')
    check(status1['audio_calls'] == end, 'real PCM sink used for each host frame')
    reset_evidence = None
    if appliance:
        check(status1['media_calls'] == end, 'unoccupied execution host publishes every actual frame')
        check(status2['media_calls'] == 0, 'remote P1 never becomes spectator publisher')
        # Actual reset contract: close old core, fresh epoch/token and deterministic
        # reset state; client-side identity gate rejects late old-epoch frames.
        previous = ready1['lease']
        p1.command('start', port=-1, execution_host=True, epoch=2)
        reset_ready = p1.wait_event('ready', lambda e: e['generation'] == 2)
        check(reset_ready['epoch'] == 2 and reset_ready['lease'] != previous, 'reset obtains independent runtime epoch and token')
        check(reset_ready['hash'] == ready1['hash'], 'reset recreates identical initial core state')
        p1.command('old_epoch')
        send(p1, 0, [(0, 0)]*8)
        reset_frame = await_frame(p1, 8, 2)
        check(reset_frame['video'] == p1.frames(1)[8]['video'], 'reset starts at original frame with neutral controllers')
        p1.command('close')
        reset_status = p1.command('poll')
        check(not reset_status['lease'] and reset_status['media_calls'] == 8, 'reset epoch releases its real core and publishes neutral frames')
        reset_evidence = {'epoch': 2, 'neutral_frames': 8, 'late_epoch_rejected': True, 'deterministic_initial_state': True}
        status1 = reset_status
    return {'passed': True, 'coordinator_assertions': assertions,
            'worker_assertions': status1['assertions']+status2['assertions'],
            'actual_codec_roundtrips': status1['codecs']+status2['codecs'],
            'actual_playback_worker_jvms': 2, 'guest_worker_generations': 3,
            'host_final_next_frame': end, 'matched_video_and_pcm_frames': len(trace)+len(follow),
            'actual_button_port_cases': len(color_cases), 'same_tick_edge_masks': edges,
            'jitter_milliseconds': sorted(set(jitter)), 'joins': [first_join, second_join],
            'rejected_guest_gap_error': guest_error,
            'queued_ready_suppressed_after_close': True, 'host_worker_restart_count': 0,
            'appliance_roles': appliance, 'host_control_port': -1 if appliance else 0,
            'guest_control_port': 0 if appliance else 1, 'explicit_reset': reset_evidence}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    for name in ('fc', 'sfc', 'report'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--production-source', action='store_true', help='Compile current production sources over baseline jars; evidence is NOT final-jar-only.')
    parser.add_argument('--appliance', action='store_true', help='Use independent execution host port -1 and remote controller P1, then explicitly reset epoch.')
    args = parser.parse_args()
    if args.report.exists():
        raise ValueError('Evidence reports are immutable; choose a new report path')
    paths = {key: getattr(args, key).resolve(strict=True) for key in ('fc', 'sfc')}
    before = {key: sha(path) for key, path in paths.items()}
    cache = Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    manifest = json.loads((MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    vanilla = []
    for lib in manifest['libraries']:
        parts = lib['name'].split(':')
        if len(parts) == 3:
            vanilla.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps = vanilla+[p for p in cache.rglob('*.jar') if not any(x in p.name for x in ('-sources', '-javadoc', '-userdev'))]
    probes = [ROOT/'tools/qa/SfcPlaybackWorkerProbe.java', ROOT.parent/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',
              ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    if args.appliance:
        probes.append(ROOT/'tools/qa/SfcApplianceProtocolProbe.java')
    production = sorted((ROOT/'src/main/java').rglob('*.java')) if args.production_source else []
    source_hashes = {str(p.relative_to(ROOT.parent)): sha(p) for p in probes+production+[Path(__file__).resolve()]}
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='piq-sfc-playback-two-jvm-') as directory:
        tmp = Path(directory)
        classes, empty = tmp/'classes', tmp/'empty'
        classes.mkdir(); empty.mkdir()
        copies = {}
        for key, path in paths.items():
            copies[key] = tmp/(key+'.jar')
            shutil.copyfile(path, copies[key])
            assert sha(copies[key]) == before[key]
        resources = MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp = os.pathsep.join(map(str, [classes, MC, resources, copies['sfc'], copies['fc'], *deps]))
        argfile = tmp/'classpath.args'
        argfile.write_text('-cp\n"'+cp.replace('\\', '/')+'"\n', encoding='utf-8')
        compile_output = run([JAVA/'javac.exe', '@'+str(argfile), '-encoding', 'UTF-8', '-proc:none', '-sourcepath', empty,
                              '-d', classes, *production, *probes], tmp)
        expected = classes if args.production_source else copies['sfc']
        command = [JAVA/'java.exe', '-Xmx2G', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                   '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '@'+str(argfile),
                   'cn.piq.sfchome.client.SfcPlaybackWorkerProbe', expected, copies['sfc'], copies['fc']]
        appliance_protocol = None
        if args.appliance:
            protocol_output = run([JAVA/'java.exe', '-Xmx1G', '-Djava.awt.headless=true', '@'+str(argfile),
                                   'cn.piq.sfchome.net.SfcApplianceProtocolProbe', expected], tmp)
            appliance_protocol = json.loads(next(line for line in reversed(protocol_output.splitlines()) if line.startswith('{"ok":')))
        workers = []
        try:
            workers.append(Worker(command, tmp, 'P1'))
            workers.append(Worker(command, tmp, 'P2'))
            actual = exercise(*workers, appliance=args.appliance)
        finally:
            for worker in workers:
                worker.finish()
        assert all(sha(path) == before[key] and sha(copies[key]) == before[key] for key, path in paths.items())
    changed = [name for name, digest in source_hashes.items() if sha(ROOT.parent/name) != digest]
    if changed:
        raise AssertionError('Sources changed during probe; evidence discarded: '+str(changed))
    report = {'passed': True, 'mode': 'production-source' if args.production_source else 'final-jar-only',
              'production_compiled': bool(production), 'elapsed_seconds': round(time.monotonic()-started, 3),
              'actual': actual, 'appliance_protocol': appliance_protocol, 'jars': {key: {'path': str(path), 'sha256': before[key]} for key, path in paths.items()},
              'source_sha256': source_hashes, 'compile_output': compile_output,
              'scope': 'Two real production SfcPlayback workers, unmodified WASM cores, actual paced frame queues/core leases, real record codecs and SfcJoinGate consent/snapshot checks, original input-sensitive 65816 ROM.',
              'limitations': ['No Minecraft client/server, socket, real packet dispatch, protection plugin or real controller was started.',
                             'Python is the coordinator; server lease/permission/session transitions are not integration-tested by this fixture.',
                             'Host callback dispatch/audio/backup effects are test sinks; production SfcJoinClient upload/cancellation routing is not exercised.',
                             'Timeout gate uses an advanced logical tick, not a 30-second live network timeout.',
                             'Only an original diagnostic ROM was used; this does not assert commercial game compatibility.'],
              'commercial_roms': False, 'minecraft_started': False, 'network_socket_opened': False,
              'audio_device_opened': False, 'installed': False}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x', encoding='utf-8') as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in report.items() if k != 'source_sha256'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
