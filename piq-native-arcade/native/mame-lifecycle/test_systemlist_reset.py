"""Bounded regression of real upstream/patched UI-cache methods, not a core test.

Opt in with PIQ_TEST_MAME_SOURCE_ZIP (the pinned existing archive) and
PIQ_TEST_CLANGXX (an explicit compiler); no downloads or MAME builds occur.
The small fixture replaces only data types and wraps a real std::thread so
join releases a deterministic worker barrier. Both reset_cache and
notify_available are extracted verbatim, retaining their real std::mutex.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

import build_candidate as build


SYSTEMLIST = "src/frontend/mame/ui/systemlist.cpp"


def method(source, signature):
    """Extract the fixed source's simple method (no braces in string literals)."""
    if source.count(signature) != 1:
        raise ValueError("Expected one exact method signature")
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for end in range(opening, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            return source[start:end + 1]
    raise ValueError("Unterminated method")


HARNESS = r'''
#include <atomic>
#include <condition_variable>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

void require(bool value) { if (!value) std::abort(); }
struct gate {
    std::mutex mutex;
    std::condition_variable condition;
    bool released = false;
    void open() {
        std::lock_guard<std::mutex> lock(mutex);
        released = true;
        condition.notify_all();
    }
    void wait() {
        std::unique_lock<std::mutex> lock(mutex);
        condition.wait(lock, [this] { return released; });
    }
};
// Only scheduling instrumentation: join still waits for a real std::thread.
struct gated_thread {
    gate &barrier;
    std::thread thread;
    gated_thread(gate &g, std::function<void()> f) : barrier(g), thread(std::move(f)) {}
    bool joinable() { return thread.joinable(); }
    void join() {
        std::cout << "join-barrier-released" << std::endl;
        barrier.open();
        thread.join();
    }
};
struct machine_filter_data { int dirty = 0; };
class system_list {
public:
    enum available : unsigned { AVAIL_NONE = 0, AVAIL_READY = 1 };
    std::mutex m_mutex;
    std::condition_variable m_condition;
    std::unique_ptr<gated_thread> m_thread;
    std::atomic<bool> m_started{false};
    std::atomic<unsigned> m_available{AVAIL_NONE};
    std::vector<int> m_systems, m_sorted_list;
    machine_filter_data m_filter_data;
    int m_bios_count = 0;
    std::atomic<int> finished{0};
    void reset_cache();
    void notify_available(available value);
    void start(gate &barrier) {
        require(!m_thread && !m_started);
        m_started = true;
        m_systems = {1}; m_sorted_list = {1};
        m_filter_data.dirty = 1; m_bios_count = 1;
        m_thread = std::make_unique<gated_thread>(barrier, [this, &barrier] {
            barrier.wait();
            // reset must preserve started until this worker has completed.
            require(m_started);
            std::cout << "worker-notify-attempt" << std::endl;
            notify_available(AVAIL_READY);
            ++finished;
        });
    }
    void check_reset() {
        require(!m_thread && !m_started && m_available == AVAIL_NONE);
        require(m_systems.empty() && m_sorted_list.empty());
        require(m_filter_data.dirty == 0 && m_bios_count == 0);
    }
};
@RESET@
@NOTIFY@
int main(int argc, char **argv) {
    require(argc == 2);
    std::string mode(argv[1]);
    system_list data;
    data.reset_cache(); data.check_reset();
    data.reset_cache(); data.check_reset();
    if (mode != "empty") {
        const int count = mode == "restart" ? 3 : 1;
        for (int cycle = 1; cycle <= count; ++cycle) {
            gate barrier;
            data.start(barrier);
            if (mode == "completed" || mode == "already-joined") {
                barrier.open();
                while (data.finished < cycle) std::this_thread::yield();
                if (mode == "already-joined") data.m_thread->join();
            }
            data.reset_cache(); data.check_reset();
            require(data.finished == cycle);
            data.reset_cache(); data.check_reset();
        }
    }
    std::cout << "PASS " << mode << std::endl;
}
'''


class ExtractionTests(unittest.TestCase):
    def test_nested_method_is_kept_verbatim(self):
        expected = "void f() { if (ok) { work(); } }"
        self.assertEqual(method("prefix\n" + expected + "\nvoid g() {}", "void f()"), expected)

    def test_missing_duplicate_or_unterminated_is_rejected(self):
        for source in ("void g() {}", "void f() {} void f() {}", "void f() {"):
            with self.subTest(source=source), self.assertRaises(ValueError):
                method(source, "void f()")


@unittest.skipUnless(os.environ.get("PIQ_TEST_MAME_SOURCE_ZIP")
                     and os.environ.get("PIQ_TEST_CLANGXX") and shutil.which("git"),
                     "requires an existing pinned source ZIP, explicit Clang++, and Git")
class RealMethodRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        archive_path = Path(os.environ["PIQ_TEST_MAME_SOURCE_ZIP"])
        if build.sha(archive_path) != build.SOURCE_SHA256 or build.sha(build.PATCH) != build.PATCH_SHA256:
            raise ValueError("Source or patch identity mismatch")
        compiler = Path(os.environ["PIQ_TEST_CLANGXX"]).resolve(strict=True)
        cls.temp = tempfile.TemporaryDirectory(prefix="piq-systemlist-reset-",
                                             dir=os.environ.get("PIQ_TEST_HARNESS_PARENT"))
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        with zipfile.ZipFile(archive_path) as archive:
            for name in sorted(build.CHANGED):
                path = cls.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(f"mame-{build.COMMIT}/" + name))
        cls.original = (cls.root / SYSTEMLIST).read_text(encoding="utf-8")
        for flags in (("--check",), ()):
            subprocess.run([shutil.which("git"), "-c", "core.autocrlf=false", "apply",
                            *flags, str(build.PATCH)], cwd=cls.root,
                           check=True, capture_output=True, timeout=30)
        cls.patched = (cls.root / SYSTEMLIST).read_text(encoding="utf-8")
        cls.env = os.environ | {"PATH": str(compiler.parent) + os.pathsep + os.environ.get("PATH", "")}
        cls.executables = {}
        for name, source in (("original", cls.original), ("patched", cls.patched)):
            harness = HARNESS.replace("@RESET@", method(source, "void system_list::reset_cache()"))
            harness = harness.replace("@NOTIFY@", method(source, "void system_list::notify_available(available value)"))
            source_path = cls.root / (name + ".cpp")
            source_path.write_text(harness, encoding="utf-8")
            binary = cls.root / (name + (".exe" if os.name == "nt" else ""))
            subprocess.run([str(compiler), "-std=c++17", "-O0", "-pthread", str(source_path), "-o", str(binary)],
                           cwd=cls.root, env=cls.env, check=True, capture_output=True, timeout=60)
            cls.executables[name] = binary

    def run_fixture(self, version, mode, timeout=5):
        return subprocess.run([str(self.executables[version]), mode], cwd=self.root, env=self.env,
                              check=True, capture_output=True, text=True, timeout=timeout)

    def test_old_reset_deterministically_blocks_worker_notification(self):
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            self.run_fixture("original", "worker", timeout=3)
        # A start-up failure or missing runtime is not the negative control.
        output = caught.exception.stdout or b""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        self.assertIn("join-barrier-released", output)
        self.assertIn("worker-notify-attempt", output)
        self.assertNotIn("PASS", output)

    def test_both_empty_resets_are_idempotent(self):
        for version in self.executables:
            with self.subTest(version=version):
                self.assertIn("PASS empty", self.run_fixture(version, "empty").stdout)

    def test_patched_pending_worker_completes_before_clear(self):
        self.assertIn("PASS worker", self.run_fixture("patched", "worker").stdout)

    def test_patched_three_restarts_and_repeated_resets(self):
        self.assertIn("PASS restart", self.run_fixture("patched", "restart").stdout)

    def test_patched_completed_and_already_joined_workers(self):
        for mode in ("completed", "already-joined"):
            with self.subTest(mode=mode):
                self.assertIn("PASS " + mode, self.run_fixture("patched", mode).stdout)

    def test_actual_finish_resets_cache_before_osd_and_unconditional_deinit(self):
        finish = method((self.root / "src/frontend/mame/mame.cpp").read_text(encoding="utf-8"), "void retro_finish()")
        calls = ("free_machineconfig();", "free_man();", "ui::system_list::instance().reset_cache();",
                 "retro_finish_osd();")
        self.assertEqual([finish.index(call) for call in calls], sorted(finish.index(call) for call in calls))
        source = (self.root / "src/osd/libretro/libretro-internal/libretro.cpp").read_text(encoding="utf-8")
        deinit = method(source, "void retro_deinit(void)")
        self.assertNotIn("if (retro_load_ok)", deinit)
        self.assertLess(deinit.index("retro_finish();"), deinit.index("free_output_audio_buffer();"))


if __name__ == "__main__":
    unittest.main()
