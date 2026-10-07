"""Pure Python/fake API tests. No test loads a real candidate DLL."""
import ctypes as c
import copy
import io
import json
from pathlib import Path
import struct
import tempfile
import threading
import time
import unittest
from unittest import mock
import zipfile

import verify_candidate as target


def clean_sample(**changes):
    return {"thread_count": 1, "new_threads": 0, "candidate_start_threads": 0,
            "unmapped_start_threads": 0, "thread_query_errors": 0,
            "handle_count": 20, "handle_delta": 0, "module_count": 10,
            "module_delta": 0, "candidate_modules": 0, **changes}


class Clock:
    def __init__(self):
        self.now = 0.0

    def clock(self):
        return self.now

    def pause(self, seconds):
        self.now += seconds


class FakeCore:
    def __init__(self, frontend=None, load=True):
        self.frontend, self.load = frontend, load
        self.calls = []
        self._handle = 1234

    def retro_init(self):
        self.calls.append("init")

    def retro_load_game(self, game):
        self.calls.append("load")
        return self.load

    def retro_run(self):
        self.calls.append("run")
        self.frontend.video(1, 256, 224, 1024)
        self.frontend.batch(None, 800)

    def retro_unload_game(self):
        self.calls.append("unload")

    def retro_deinit(self):
        self.calls.append("deinit")


class VerificationTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.core = self.root / "candidate.dll"
        raw = bytearray(4096)
        raw[:2] = b"MZ"
        struct.pack_into("<I", raw, 0x3C, 0x80)
        raw[0x80:0x84] = b"PE\0\0"
        struct.pack_into("<H", raw, 0x84, 0x8664)
        struct.pack_into("<H", raw, 0x94, 240)
        struct.pack_into("<H", raw, 0x96, 0x2000)
        struct.pack_into("<H", raw, 0x98, 0x20B)
        struct.pack_into("<I", raw, 0x98 + 56, 8192)
        self.core.write_bytes(raw)
        self.expected = target.sha(self.core)
        self.output = self.root / "new-output"
        self.clock = Clock()
        for name in ("CDLL", "WinDLL"):
            patcher = mock.patch.object(target.c, name,
                side_effect=AssertionError("unit tests must not load native libraries"), create=True)
            patcher.start()
            self.addCleanup(patcher.stop)

    def assert_code(self, code, action):
        with self.assertRaises(target.ProbeError) as raised:
            action()
        self.assertEqual(code, raised.exception.code)

    def test_new_sha_mandatory_and_original_baseline_prohibited(self):
        for value in ("", "0" * 63, "G" * 64, target.OLD_CORE_SHA.upper()):
            self.assert_code("invalid-sha", lambda: target.candidate_sha(value))
        self.assertEqual("a" * 64, target.candidate_sha("A" * 64))

    def test_pe_and_sha_checked_without_loading(self):
        self.assertEqual("amd64", target.verify_core(self.core, self.expected)["machine"])
        self.assert_code("core-sha-mismatch", lambda: target.verify_core(self.core, "a" * 64))
        original = self.core.read_bytes()
        changes = ((0, b"XX"), (0x3C, struct.pack("<I", 4096)),
                   (0x80, b"ELF!"), (0x84, struct.pack("<H", 0x14C)),
                   (0x94, struct.pack("<H", 64)), (0x96, b"\0\0"),
                   (0x98, struct.pack("<H", 0x10B)), (0x98 + 56, b"\0\0\0\0"))
        for offset, value in changes:
            with self.subTest(offset=offset):
                raw = bytearray(original)
                raw[offset:offset + len(value)] = value
                self.core.write_bytes(raw)
                self.assert_code("invalid-pe", lambda: target.verify_core(self.core, target.sha(self.core)))

    def test_existing_output_not_overwritten_and_bad_hash_creates_nothing(self):
        self.assert_code("core-sha-mismatch",
                         lambda: target.prepare(self.core, "a" * 64, self.output))
        self.assertFalse(self.output.exists())
        self.output.mkdir()
        marker = self.output / "keep.txt"
        marker.write_text("keep", encoding="utf-8")
        self.assert_code("existing-output", lambda: target.prepare(self.core, self.expected, self.output))
        self.assertEqual("keep", marker.read_text(encoding="utf-8"))

    def test_reparse_point_rejected_before_resolve(self):
        info = mock.Mock(st_mode=0, st_file_attributes=0x400)
        with mock.patch.object(Path, "lstat", return_value=info):
            self.assert_code("unsafe-path", lambda: target.safe_path(self.core))

    def test_original_generator_reused_and_missing_three_chips_is_synthetic(self):
        receipt = target.prepare(self.core, self.expected, self.output)
        staging = self.output / "staging"
        self.assertEqual(target.HEADER_SHA, target.sha(target.HEADER))
        self.assertEqual(target.GENERATOR_SHA, target.sha(target.GENERATOR))
        with zipfile.ZipFile(staging / "original/invaders.zip") as full:
            with zipfile.ZipFile(staging / "missing/invaders.zip") as missing:
                self.assertEqual(4, len(full.namelist()))
                self.assertEqual(full.namelist()[:1], missing.namelist())
                first = missing.namelist()[0]
                self.assertEqual(full.read(first), missing.read(first))
        self.assertEqual(self.expected, target.sha(staging / "core.dll"))
        for case in target.CASES:
            work = staging / case
            launch = json.loads((work / "launch.json").read_text(encoding="utf-8"))
            self.assertEqual(target.sha(work / "content/invaders.zip"), launch["content_sha256"])
        serialized = json.dumps(receipt)
        self.assertNotIn(str(self.root), serialized)
        self.assertEqual("native-libretro-only", receipt["scope"])
        self.assertFalse(receipt["java_jni_verified"])
        self.assertFalse(receipt["minecraft_verified"])

    def test_generator_pin_failure_does_not_execute_modified_source(self):
        with mock.patch.object(target, "GENERATOR_SHA", "0" * 64):
            self.assert_code("source-pin-mismatch", lambda: target.make_content(self.root))
        self.assertFalse((self.root / "original").exists())

    def run_lifecycle(self, case, load=True):
        frontend = target.Frontend(self.root)
        api = FakeCore(frontend, load)
        events = []
        game = target.GameInfo(b"invaders.zip", None, 0, None)
        target.lifecycle(api, frontend, case, game,
                         lambda phase, **fields: events.append((phase, fields)),
                         pause=self.clock.pause, clock=self.clock.clock)
        return api, frontend, events

    def test_no_load_calls_one_deinit_and_never_unload_or_run(self):
        api, _, _ = self.run_lifecycle("no-load")
        self.assertEqual(["init", "deinit"], api.calls)

    def test_double_deinit_calls_same_object_twice(self):
        api, _, events = self.run_lifecycle("double-deinit")
        self.assertEqual(["init", "deinit", "deinit"], api.calls)
        self.assertEqual([1, 2], [row[1]["call"] for row in events if row[0] == "after-deinit"])

    def test_failed_load_does_not_run_or_unload_but_deinitializes_twice(self):
        api, _, events = self.run_lifecycle("failed-double-deinit", load=False)
        self.assertEqual(["init", "load", "deinit", "deinit"], api.calls)
        self.assertIn(("expected-load-rejection", {"loaded": False}), events)

    def test_success_runs_180_frames_then_unloads_then_double_deinit(self):
        api, frontend, _ = self.run_lifecycle("success-double-deinit")
        self.assertEqual(["init", "load"] + ["run"] * 180 + ["unload", "deinit", "deinit"], api.calls)
        self.assertEqual(180, frontend.video_frames)
        self.assertEqual(180 * 800, frontend.audio_frames)

    def test_unexpected_load_result_is_failure_not_claimed_rejection(self):
        self.assert_code("load-result", lambda: self.run_lifecycle("failed-double-deinit", load=True))
        self.assert_code("load-result", lambda: self.run_lifecycle("success-double-deinit", load=False))

    def test_success_without_actual_video_or_audio_is_rejected(self):
        with mock.patch.object(FakeCore, "retro_run", lambda api: api.calls.append("run")):
            self.assert_code("no-media", lambda: self.run_lifecycle("success-double-deinit"))

    def test_linger_is_330_seconds_after_close_and_records_final_sample(self):
        events = []
        self.clock.now = 100
        target.retain(lambda phase, **fields: events.append((phase, fields)),
                      pause=self.clock.pause, clock=self.clock.clock)
        self.assertEqual(430, self.clock.now)
        self.assertEqual(67, len(events))
        self.assertEqual(0, events[0][1]["linger_seconds"])
        self.assertEqual(330, events[-1][1]["linger_seconds"])

    def test_resource_failures_are_not_turned_into_success(self):
        self.assertTrue(target.recovered(clean_sample()))
        self.assertTrue(target.recovered(clean_sample(handle_delta=-1, module_delta=1)))
        for field in ("candidate_modules", "new_threads", "candidate_start_threads",
                      "unmapped_start_threads", "thread_query_errors", "handle_delta"):
            with self.subTest(field=field):
                self.assertFalse(target.recovered(clean_sample(**{field: 1})))

    def test_cycle_verdict_uses_the_same_os_snapshot_as_the_receipt(self):
        stream = io.StringIO()
        observer = mock.Mock()
        observer.take.side_effect = [clean_sample(handle_delta=1), clean_sample()]
        recorder = target.Recorder(stream, observer, clock=self.clock.clock)
        event = recorder("cycle-completed")
        self.assertFalse(event["recovered"])
        self.assertEqual(1, observer.take.call_count)
        self.assertEqual(event, json.loads(stream.getvalue()))

    def test_receipt_event_allowlist_does_not_accept_paths_errors_or_environment(self):
        for bad in ({"phase": "private-path"}, {"phase": "failed", "error": "secret"},
                    {"phase": "baseline", "username": "private"},
                    {"phase": "baseline", "handle_count": "private"},
                    {"phase": "baseline", "handle_count": float("nan")},
                    {"phase": "completed", "recovered": 1}):
            self.assert_code("invalid-events", lambda: target.validate_event(bad))
        self.assertEqual("internal-error", target.ProbeError("private exception").code)

    def fake_events(self, case):
        stream, observer = io.StringIO(), mock.Mock()
        mapped = [False]
        observer.take.side_effect = lambda: clean_sample(candidate_modules=int(mapped[0]))
        record = target.Recorder(stream, observer, clock=self.clock.clock)
        record("baseline")
        for cycle in range(1, 4 if case == "three-cycles" else 2):
            record.cycle = cycle
            mapped[0] = True
            record("dll-loaded")
            frontend = target.Frontend(self.root)
            api = FakeCore(frontend, load=case != "failed-double-deinit")
            target.lifecycle(api, frontend, case, target.GameInfo(), record,
                             pause=self.clock.pause, clock=self.clock.clock)
            record("free-library", video_frames=frontend.video_frames, audio_frames=frontend.audio_frames)
            mapped[0] = False
            record("after-free")
            target.retain(record, pause=self.clock.pause, clock=self.clock.clock)
            record("cycle-completed")
        record("completed")
        return [json.loads(line) for line in stream.getvalue().splitlines()]

    def test_parent_verdict_requires_complete_330_second_os_trail_for_every_route(self):
        for case in target.CASES:
            with self.subTest(case=case):
                events = self.fake_events(case)
                self.assertTrue(target.events_passed(case, events))
                self.assertFalse(target.events_passed(case, events[:-1]))
                self.assertFalse(target.events_passed(case,
                    [row for row in events if row["phase"] != "after-deinit"]))
                too_short = copy.deepcopy(events)
                for row in too_short:
                    if row["phase"] == "linger":
                        row["linger_seconds"] = min(329, row["linger_seconds"])
                self.assertFalse(target.events_passed(case, too_short))
                stale_thread = copy.deepcopy(events)
                next(row for row in stale_thread if row["phase"] == "after-free")["candidate_start_threads"] = 1
                self.assertFalse(target.events_passed(case, stale_thread))
                false_recovered = copy.deepcopy(events)
                next(row for row in false_recovered if row["phase"] == "cycle-completed")["handle_delta"] = 1
                self.assertFalse(target.events_passed(case, false_recovered))

    def test_parent_launch_discards_raw_logs_and_writes_only_allowlisted_events(self):
        target.prepare(self.core, self.expected, self.output)
        events = self.fake_events("success-double-deinit")
        process = mock.Mock()
        process.poll.return_value = 0
        process.wait.return_value = 0
        with mock.patch.object(target.subprocess, "Popen", return_value=process) as popen, \
             mock.patch.object(target.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), \
             mock.patch.object(target.subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0x4000, create=True), \
             mock.patch.object(target, "read_events", return_value=events):
            result = target.run_case(self.output, self.expected, "success-double-deinit")
        self.assertTrue(result["passed"])
        _, options = popen.call_args
        for key in ("stdin", "stdout", "stderr"):
            self.assertEqual(target.subprocess.DEVNULL, options[key])
        command = popen.call_args.args[0]
        self.assertIn("-I", command)
        self.assertEqual("success-double-deinit", command[-1])
        receipt_path = self.output / "receipts/success-double-deinit.json"
        serialized = receipt_path.read_text(encoding="utf-8")
        self.assertNotIn(str(self.root), serialized)
        self.assertNotIn("parent_pid", serialized)
        self.assertNotIn("command", serialized)
        self.assertEqual(events, json.loads(serialized)["events"])

    def test_failed_process_cannot_pass_with_a_completed_trail(self):
        target.prepare(self.core, self.expected, self.output)
        events = self.fake_events("no-load")
        with mock.patch.object(target.subprocess, "Popen"), \
             mock.patch.object(target.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), \
             mock.patch.object(target.subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0x4000, create=True), \
             mock.patch.object(target, "supervise", return_value=(0xC0000005, False, 400)), \
             mock.patch.object(target, "read_events", return_value=events):
            result = target.run_case(self.output, self.expected, "no-load")
        self.assertFalse(result["passed"])
        self.assertEqual(0xC0000005, result["exit_code"])

    def test_sparse_affinity_selects_at_most_two_available_cpus(self):
        self.assertEqual(0b10100, target.limited_affinity(0b1010100))
        self.assertEqual(0b1000, target.limited_affinity(0b1000))
        self.assert_code("os-observation", lambda: target.limited_affinity(0))

    def test_timeout_kills_only_the_supplied_child(self):
        process = mock.Mock()
        alive = [True]
        process.poll.side_effect = lambda: None if alive[0] else 99
        process.kill.side_effect = lambda: alive.__setitem__(0, False)
        process.wait.return_value = 99
        code, timeout, elapsed = target.supervise(process, 3,
            pause=self.clock.pause, clock=self.clock.clock)
        self.assertEqual((99, True, 3), (code, timeout, elapsed))
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with()

    def test_normal_child_exit_is_not_killed(self):
        process = mock.Mock()
        process.poll.return_value = 0
        process.wait.return_value = 0
        self.assertEqual((0, False, 0), target.supervise(process, 3,
                         pause=self.clock.pause, clock=self.clock.clock))
        process.kill.assert_not_called()

    def test_interrupted_observer_reaps_its_child(self):
        process = mock.Mock()
        alive = [True]
        process.poll.side_effect = lambda: None if alive[0] else 1
        process.kill.side_effect = lambda: alive.__setitem__(0, False)
        def interrupted(_):
            raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            target.supervise(process, 3, pause=interrupted, clock=self.clock.clock)
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with()

    def test_cancel_before_launch_never_creates_a_child(self):
        target.prepare(self.core, self.expected, self.output)
        cancel = threading.Event()
        cancel.set()
        with mock.patch.object(target.subprocess, "Popen") as popen:
            result = target.run_case(self.output, self.expected, "no-load", cancel)
        self.assertTrue(result["cancelled"])
        self.assertFalse(result["passed"])
        popen.assert_not_called()

    def test_main_thread_interrupt_stops_two_active_children_and_cancels_queued_cases(self):
        target.prepare(self.core, self.expected, self.output)
        processes, both_started, lock = [], threading.Event(), threading.Lock()
        def start(*args, **kwargs):
            process = mock.Mock()
            alive = [True]
            process.poll.side_effect = lambda: None if alive[0] else 1
            process.kill.side_effect = lambda: alive.__setitem__(0, False)
            process.wait.return_value = 1
            with lock:
                processes.append(process)
                if len(processes) == 2:
                    both_started.set()
            return process
        real_executor, supervise = target.ThreadPoolExecutor, target.supervise
        class InterruptingExecutor:
            def __init__(self, **kwargs):
                self.pool, self.first = real_executor(**kwargs), True
            def submit(self, *args, **kwargs):
                future = self.pool.submit(*args, **kwargs)
                if self.first:
                    self.first = False
                    def interrupted_result():
                        if not both_started.wait(timeout=3):
                            raise AssertionError("two fake children did not start")
                        raise KeyboardInterrupt
                    future.result = interrupted_result
                return future
            def shutdown(self, **kwargs):
                self.pool.shutdown(**kwargs)
        def fast_supervise(process, timeout, **kwargs):
            return supervise(process, timeout, pause=lambda seconds: time.sleep(0.001), **kwargs)
        with mock.patch.object(target, "ThreadPoolExecutor", InterruptingExecutor), \
             mock.patch.object(target.subprocess, "Popen", side_effect=start), \
             mock.patch.object(target.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), \
             mock.patch.object(target.subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0x4000, create=True), \
             mock.patch.object(target, "supervise", side_effect=fast_supervise):
            with self.assertRaises(KeyboardInterrupt):
                target.run_cases(self.output, self.expected)
        self.assertEqual(2, len(processes))
        for process in processes:
            process.kill.assert_called_once_with()
            process.wait.assert_called_once_with()

    def test_three_cycles_load_free_and_retain_each_one_330_seconds(self):
        target.prepare(self.core, self.expected, self.output)
        work = self.output / "staging/three-cycles"
        core = self.output / "staging/core.dll"
        observer = mock.Mock(affinity_cpus=2)
        loaded = [False]
        observer.take.side_effect = lambda: clean_sample(candidate_modules=int(loaded[0]))
        freed = []
        def free(handle):
            self.assertEqual(1234, handle)
            self.assertTrue(loaded[0])
            loaded[0] = False
            freed.append(self.clock.now)
            return True
        observer.free.side_effect = free
        observer.mapped.return_value = None
        instances, starts = [], []
        def load(*args, **kwargs):
            self.assertEqual({"winmode": 0x900}, kwargs)
            loaded[0] = True
            starts.append(self.clock.now)
            instance = FakeCore()
            instances.append(instance)
            return instance
        recorder, lifecycle, retain = target.Recorder, target.lifecycle, target.retain
        with mock.patch.object(Path, "cwd", return_value=work), \
             mock.patch.object(target.os, "getppid", return_value=target.os.getpid()), \
             mock.patch.object(target, "WindowsObserver", return_value=observer), \
             mock.patch.object(target.c, "CDLL", side_effect=load), \
             mock.patch.object(target, "bind_core", side_effect=lambda api, frontend: setattr(api, "frontend", frontend)), \
             mock.patch.object(target, "Recorder", side_effect=lambda stream, obs: recorder(stream, obs, clock=self.clock.clock)), \
             mock.patch.object(target, "lifecycle", side_effect=lambda *args: lifecycle(*args, pause=self.clock.pause, clock=self.clock.clock)), \
             mock.patch.object(target, "retain", side_effect=lambda record: retain(record, pause=self.clock.pause, clock=self.clock.clock)):
            self.assertEqual(0, target.child(core, self.expected, work, "three-cycles"))
        self.assertEqual(3, len(instances))
        for instance in instances:
            self.assertEqual(["init", "load"] + ["run"] * 180 + ["unload", "deinit"], instance.calls)
        for start, previous_free in zip(starts[1:], freed):
            self.assertGreaterEqual(start - previous_free, 330)
        self.assertGreaterEqual(self.clock.now - freed[-1], 330)
        events = target.read_events(work / "events.jsonl")
        self.assertEqual("completed", events[-1]["phase"])
        self.assertEqual(3, sum(event["phase"] == "cycle-completed" for event in events))
        self.assertEqual(3, sum(event.get("linger_seconds") == 330 for event in events))
        self.assertTrue(target.events_passed("three-cycles", events))

    def test_os_observer_tracks_candidate_threads_even_after_module_unload(self):
        observer = target.WindowsObserver.__new__(target.WindowsObserver)
        observer.current, observer.baseline = 999, None
        observer.candidate_ranges = []
        observer.modules = mock.Mock(return_value=[(0x2000, 0x3000, False)])
        observer.snapshot = mock.Mock(return_value=100)
        observer.close = mock.Mock(return_value=True)
        rows, addresses, handles = [42], {42: 0x2100}, [20]
        position = [0]
        def write_entry(pointer):
            if position[0] >= len(rows):
                return False
            entry = c.cast(pointer, c.POINTER(target.ThreadEntry)).contents
            entry.pid, entry.tid = target.os.getpid(), rows[position[0]]
            return True
        def first(snapshot, pointer):
            position[0] = 0
            return write_entry(pointer)
        def next_entry(snapshot, pointer):
            position[0] += 1
            return write_entry(pointer)
        def query(handle, kind, address, size, unused):
            c.cast(address, c.POINTER(c.c_void_p))[0] = addresses[handle]
            return 0
        def count(handle, pointer):
            c.cast(pointer, c.POINTER(c.c_uint32))[0] = handles[0]
            return True
        observer.first, observer.next = first, next_entry
        observer.open_thread = lambda access, inherit, tid: tid
        observer.query_thread, observer.handles = query, count
        with mock.patch.object(target.c, "get_last_error", return_value=18, create=True):
            baseline = observer.take()
            self.assertTrue(target.recovered(baseline))
            rows.append(43)
            addresses[43] = 0x1100
            handles[0] = 21
            observer.candidate_ranges.append((0x1000, 0x2000))
            observer.modules.return_value.append((0x1000, 0x2000, True))
            running = observer.take()
            self.assertEqual(1, running["new_threads"])
            self.assertEqual(1, running["candidate_start_threads"])
            self.assertEqual(1, running["candidate_modules"])
            self.assertEqual(1, running["handle_delta"])
            observer.modules.return_value = [(0x2000, 0x3000, False)]
            leaked = observer.take()
            self.assertEqual(0, leaked["candidate_modules"])
            self.assertEqual(1, leaked["candidate_start_threads"])
            self.assertEqual(1, leaked["unmapped_start_threads"])
            self.assertFalse(target.recovered(leaked))
            rows.pop()
            handles[0] = 20
            self.assertTrue(target.recovered(observer.take()))
        self.assertEqual(4, observer.close.call_args_list.count(mock.call(100)))

    def test_child_os_setup_failure_retains_sanitized_failure_code(self):
        target.prepare(self.core, self.expected, self.output)
        work = self.output / "staging/no-load"
        core = self.output / "staging/core.dll"
        with mock.patch.object(Path, "cwd", return_value=work), \
             mock.patch.object(target.os, "getppid", return_value=target.os.getpid()), \
             mock.patch.object(target, "WindowsObserver", side_effect=target.ProbeError("os-observation")):
            self.assertEqual(1, target.child(core, self.expected, work, "no-load"))
        events = target.read_events(work / "events.jsonl")
        self.assertEqual(1, len(events))
        self.assertEqual("os-observation", events[0]["error"])
        self.assertFalse(target.events_passed("no-load", events))


class FrontendTest(unittest.TestCase):
    def setUp(self):
        self.frontend = target.Frontend(Path("fake-test-root"))

    def test_fixed_header_abi_sizes(self):
        self.assertEqual(c.sizeof(c.c_void_p) * 4, c.sizeof(target.GameInfo))
        self.assertEqual(c.sizeof(c.c_void_p) * 2, c.sizeof(target.Variable))
        self.assertEqual(28, c.sizeof(target.ThreadEntry))

    def test_options_are_copied_and_required_values_pinned(self):
        entries = (target.Variable * (len(target.PINS) + 2))()
        for index, key in enumerate(target.PINS):
            entries[index] = target.Variable(key, b"Option; enabled|disabled")
        entries[len(target.PINS)] = target.Variable(b"other", b"Other; first|second")
        self.assertTrue(self.frontend.environment(16, entries))
        self.assertEqual(target.PINS[b"mame_thread_mode"], self.frontend.values[b"mame_thread_mode"])
        self.assertEqual(b"first", self.frontend.values[b"other"])
        item = target.Variable(b"mame_thread_mode", None)
        self.assertTrue(self.frontend.environment(15, c.byref(item)))
        self.assertEqual(b"disabled", item.value)

    def test_option_support_null_queries_and_unknown_key(self):
        self.assertTrue(self.frontend.environment(15, None))
        self.assertTrue(self.frontend.environment(16, None))
        item = target.Variable(b"unknown", b"should-clear")
        self.assertTrue(self.frontend.environment(15, c.byref(item)))
        self.assertIsNone(item.value)

    def test_malformed_and_unavailable_fixed_option_is_not_silently_defaulted(self):
        for value in (b"bad", b"Mode; enabled"):
            frontend = target.Frontend(Path("fake-test-root"))
            entries = (target.Variable * 2)(target.Variable(b"mame_thread_mode", value), target.Variable())
            self.assertFalse(frontend.environment(16, entries))
            with self.assertRaises(target.ProbeError):
                frontend.check()

    def test_unsupported_variadic_async_hardware_and_vfs_are_declined(self):
        value = c.c_uint(0)
        for command in (14, 21, 22, 27, 0x1002D, 0x1002A):
            self.assertFalse(self.frontend.environment(command, c.byref(value)))

    def test_video_dupes_not_counted_as_real_software_frames(self):
        self.frontend.video(None, 256, 224, 512)
        self.assertEqual(1, self.frontend.video_callbacks)
        self.assertEqual(0, self.frontend.video_frames)
        self.frontend.video(1, 256, 224, 512)
        self.assertEqual(1, self.frontend.video_frames)
        self.assertEqual(123, self.frontend.batch(None, 123))

    def test_unnegotiated_hardware_frame_is_not_software_success(self):
        self.frontend.video(c.c_void_p(-1).value, 256, 224, 512)
        self.assertEqual(0, self.frontend.video_frames)
        with self.assertRaises(target.ProbeError):
            self.frontend.check()


if __name__ == "__main__":
    unittest.main()
