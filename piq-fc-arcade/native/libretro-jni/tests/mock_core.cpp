// SPDX-License-Identifier: GPL-3.0-or-later
// Synthetic, redistributable core for ABI tests; contains no game or firmware data.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <GL/gl.h>
#include <array>
#include <cstring>
#include "../libretro.h"
static retro_environment_t environment;
static retro_video_refresh_t video;
static retro_audio_sample_t audio;
static retro_audio_sample_batch_t batch;
static retro_input_poll_t poll;
static retro_input_state_t input;
static unsigned mode = 0, frame = 0, keyEvents = 0;
static std::array<uint8_t, 32> ram{};
static std::array<uint8_t, 8> rtc{};
static retro_hw_render_callback hw{};
#ifdef PIQ_MOCK_FULLPATH
static const retro_game_info_ext *persistentContent;
#endif
static void key(bool, unsigned, unsigned, uint16_t) { keyEvents++; }
static void resetGl() {
    if (mode == 35) {
        // Startup fails after context-reset has begun, before open returns a token.
        retro_game_geometry bad{4096, 1, 4096, 1, 1};
        environment(RETRO_ENVIRONMENT_SET_GEOMETRY, &bad);
    }
}
// Deliberately violate the core contract in the test-only core: destroy the
// frontend-owned context early so close must detect Win32 failure and quarantine.
static void destroyGl() {
    if (mode == 34 || mode == 35) {
        HGLRC context = wglGetCurrentContext();
        wglMakeCurrent(nullptr, nullptr);
        wglDeleteContext(context);
    }
}
extern "C" {
unsigned retro_api_version() { return RETRO_API_VERSION; }
void retro_set_environment(retro_environment_t callback) {
    environment = callback;
    bool yes = true;
    environment(RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME, &yes);
    static retro_variable variables[] = {{"piq_mode", "Test option; normal|other"}, {nullptr, nullptr}};
    environment(RETRO_ENVIRONMENT_SET_VARIABLES, variables);
}
void retro_set_video_refresh(retro_video_refresh_t cb) { video = cb; }
void retro_set_audio_sample(retro_audio_sample_t cb) { audio = cb; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { batch = cb; }
void retro_set_input_poll(retro_input_poll_t cb) { poll = cb; }
void retro_set_input_state(retro_input_state_t cb) { input = cb; }
void retro_get_system_info(retro_system_info *info) {
#ifdef PIQ_MOCK_FULLPATH
    *info = {"PIQ mock", "abi1", "bin", true, false};
#else
    *info = {"PIQ mock", "abi1", "bin", false, false};
#endif
}
void retro_get_system_av_info(retro_system_av_info *av) {
    av->geometry = {2, 2, 4, 4, 4.0f / 3};
    av->timing = {60, 32040};
}
void retro_init() {}
void retro_deinit() {}
bool retro_load_game(const retro_game_info *game) {
    frame = keyEvents = 0;
    mode = game && game->size ? ((const uint8_t *)game->data)[0] : 0;
#ifdef PIQ_MOCK_FULLPATH
    // Mirrors the Mesen-style persistent extended-info contract even though
    // retro_load_game itself receives only a path. Read again in retro_run.
    persistentContent = nullptr;
    if (!game || !game->path || game->data || game->size ||
        !environment(RETRO_ENVIRONMENT_GET_GAME_INFO_EXT, &persistentContent) ||
        !persistentContent || !persistentContent->persistent_data || !persistentContent->data ||
        !persistentContent->size || std::strcmp(game->path, persistentContent->full_path))
        return false;
    auto data = static_cast<const uint8_t *>(persistentContent->data);
    if (data[0] != 0x41 || data[persistentContent->size - 1] != 0x5a)
        return false;
#endif
    ram.fill(0);
    rtc.fill(0);
    if (mode == 10) {
        static retro_variable legacy[] = {{"piq_inline; 1|1 (slow) 2|2 (normal)", nullptr}, {nullptr, nullptr}};
        environment(RETRO_ENVIRONMENT_SET_VARIABLES, legacy);
    }
    retro_keyboard_callback k{key};
    environment(RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK, &k);
    if (mode >= 32 && mode <= 35) {
        hw = {};
        hw.context_type = RETRO_HW_CONTEXT_OPENGL;
        hw.context_reset = resetGl;
        hw.context_destroy = destroyGl;
        hw.bottom_left_origin = mode == 33;
        hw.depth = true;
        hw.stencil = true;
        return environment(RETRO_ENVIRONMENT_SET_HW_RENDER, &hw);
    }
    return true;
}
void retro_unload_game() {}
void retro_set_controller_port_device(unsigned, unsigned) {}
void retro_run() {
#ifdef PIQ_MOCK_FULLPATH
    auto data = static_cast<const uint8_t *>(persistentContent->data);
    if (data[0] != 0x41 || data[persistentContent->size - 1] != 0x5a) {
        retro_game_geometry bad{4096, 1, 4096, 1, 1};
        environment(RETRO_ENVIRONMENT_SET_GEOMETRY, &bad);
        return;
    }
#endif
    frame++;
    if (mode == 12 && frame == 1) Sleep(2000);
    if (mode == 13) {
        HANDLE other = CreateThread(nullptr, 0, [](void *) -> DWORD {
            unsigned rotation = 3;
            environment(RETRO_ENVIRONMENT_SET_ROTATION, &rotation);
            return 0;
        }, nullptr, 0, nullptr);
        if (other) { WaitForSingleObject(other, INFINITE); CloseHandle(other); }
    }
    poll();
    for (int p = 0; p < 4; p++) {
        int mask = input(p, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK);
        ram[p * 2] = mask;
        ram[p * 2 + 1] = mask >> 8;
    }
    ram[8] = keyEvents;
    ram[9] = input(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_PRESSED);
    ram[10] = input(0, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_RIGHT);
    ram[11] = input(0, RETRO_DEVICE_KEYBOARD, 0, 65);
    if (mode == 7) {
        retro_game_geometry bad{4096, 1, 4096, 1, 1};
        environment(RETRO_ENVIRONMENT_SET_GEOMETRY, &bad);
        return;
    }
    if (mode == 8) {
        static std::array<int16_t, 32770> tooLarge{};
        batch(tooLarge.data(), 16385);
        return;
    }
    unsigned rotation = frame % 4;
    environment(RETRO_ENVIRONMENT_SET_ROTATION, &rotation);
    if (mode >= 32 && mode <= 35) {
        auto bind = reinterpret_cast<void(APIENTRY *)(GLenum, GLuint)>(hw.get_proc_address("glBindFramebuffer"));
        bind(0x8D40, (GLuint)hw.get_current_framebuffer());
        glViewport(0, 0, 2, 2);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(1, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 1, 2, 1);
        glClearColor(0, 0, 1, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        video(RETRO_HW_FRAME_BUFFER_VALID, 2, 2, 0);
    } else if (frame == 4)
        video(nullptr, 0, 0, 0);
    else {
        retro_pixel_format format = (retro_pixel_format)((frame - 1) % 3);
        environment(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);
        if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
            uint32_t pixels[] = {0x00ff0000, 0x000000ff, 0xbadbad, 0x0000ff00, 0x00ffffff, 0xbadbad};
            video(pixels, 2, 2, 12);
        } else if (format == RETRO_PIXEL_FORMAT_RGB565) {
            uint16_t pixels[] = {0xf800, 0x001f, 0xbad, 0x07e0, 0xffff, 0xbad};
            video(pixels, 2, 2, 6);
        } else {
            uint16_t pixels[] = {0x7c00, 0x001f, 0xbad, 0x03e0, 0x7fff, 0xbad};
            video(pixels, 2, 2, 6);
        }
    }
    audio(1234, -2345);
    int16_t samples[] = {100, -100, 200, -200};
    batch(samples, 2);
}
void retro_reset() { frame = 0; }
size_t retro_serialize_size() { return 64; }
bool retro_serialize(void *target, size_t length) {
    if (length != 64 || mode == 11)
        return false;
    std::memset(target, 0, 64);
    std::memcpy(target, &frame, 4);
    std::memcpy((char *)target + 4, ram.data(), ram.size());
    std::memcpy((char *)target + 36, rtc.data(), rtc.size());
    return true;
}
bool retro_unserialize(const void *source, size_t length) {
    if (length != 64)
        return false;
    std::memcpy(&frame, source, 4);
    std::memcpy(ram.data(), (char *)source + 4, ram.size());
    std::memcpy(rtc.data(), (char *)source + 36, rtc.size());
    return true;
}
size_t retro_get_memory_size(unsigned id) {
    if (mode == 9 && id == 0)
        return 4 * 1024 * 1024 + 1;
    return id == 0 ? ram.size() : id == 1 ? rtc.size() : 0;
}
void *retro_get_memory_data(unsigned id) { return id == 0 ? ram.data() : id == 1 ? rtc.data() : nullptr; }
}
