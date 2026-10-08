// SPDX-License-Identifier: GPL-3.0-or-later
// Original libretro mock, with an actual shared-libc++ thread/join dependency.
#define retro_run piq_mock_retro_run
#include "mock_core.cpp"
#undef retro_run
#include <thread>
#include <cstdlib>
static void worker_body(int* observed) {
    static thread_local int value = 0;
    *observed = ++value;
}
extern "C" __declspec(dllexport) void retro_run() {
    int observed = 0;
    std::thread worker(worker_body, &observed);
    worker.join();
    if (observed != 1) std::abort();
    // All frontend callbacks still execute on the original libretro owner.
    piq_mock_retro_run();
}
