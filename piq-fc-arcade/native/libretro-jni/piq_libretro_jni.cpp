// SPDX-License-Identifier: GPL-3.0-or-later
// ABI 2, bounded multi-instance Windows x64 frontend. No MC/JVM/render-thread work.
// The Java resource owner verifies core provenance and SHA before calling this bridge.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <psapi.h>
#include <GL/gl.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>
#include "libretro.h"

namespace {
constexpr int ABI = 2, MAX_SESSIONS = 4, MAX_DIM = 2048, MAX_VIDEO = MAX_DIM * MAX_DIM * 4;
constexpr size_t MAX_AUDIO = 32768, MAX_STATE = 16 * 1024 * 1024, MAX_RAM = 4 * 1024 * 1024, MAX_RTC = 64 * 1024;
constexpr uintmax_t MAX_MEMORY_CONTENT = 64ULL * 1024 * 1024, MAX_FULLPATH_CONTENT = 96ULL * 1024 * 1024;
constexpr int GL_COMPAT = 1, POINTER = 2, MOUSE = 4, KEYBOARD = 8, MESEN_GUN = 16, NO_GAME = 32,
              LEGACY_INLINE_OPTIONS = 64, ALL_FEATURES = 127;
using Gen = void(APIENTRY *)(GLsizei, GLuint *);
using Bind = void(APIENTRY *)(GLenum, GLuint);
struct Session {
    jlong token = 0;
    size_t slot = 0;
    std::wstring windowClass;
    DWORD owner = GetCurrentThreadId();
    int features = 0;
    HMODULE core = nullptr, gl = nullptr;
    HWND window = nullptr;
    HDC dc = nullptr;
    HGLRC context = nullptr;
    GLuint fbo = 0, texture = 0, depth = 0;
    Bind bind = nullptr;
    bool ownsClass = false, initialized = false, loaded = false, contextReset = false, running = false;
    bool moduleRegistered = false;
    bool hardware = false, shutdown = false, duplicate = true, supportsNoGame = false, cleanupFailed = false;
    std::atomic<bool> wrongThread{false};
    char failure[512]{};
    int width = 0, height = 0, maxWidth = 0, maxHeight = 0, pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555, rotation = 0;
    double aspect = 1, fps = 60, sampleRate = 48000;
    std::string system, save, path, corePath, name, version, extension, directory, basename;
    std::vector<uint8_t> content, pixels;
    std::vector<int16_t> audio;
    std::vector<unsigned> devices;
    std::array<int, 13> input{};
    std::array<bool, 512> keys{};
    std::map<std::string, std::string> pins, defaults;
    std::map<std::string, std::vector<std::string>> offered;
    retro_hw_render_callback hw{};
    retro_keyboard_callback keyboard{};
    retro_game_info_ext extended{};
#define DECL(name) decltype(&::name) name = nullptr;
    DECL(retro_api_version)
    DECL(retro_set_environment)
    DECL(retro_set_video_refresh)
    DECL(retro_set_audio_sample) DECL(retro_set_audio_sample_batch) DECL(retro_set_input_poll)
        DECL(retro_set_input_state) DECL(retro_init) DECL(retro_get_system_info) DECL(retro_get_system_av_info)
            DECL(retro_load_game) DECL(retro_run) DECL(retro_reset) DECL(retro_unload_game) DECL(retro_deinit)
                DECL(retro_set_controller_port_device) DECL(retro_serialize_size) DECL(retro_serialize)
                    DECL(retro_unserialize) DECL(retro_get_memory_data) DECL(retro_get_memory_size)
#undef DECL
};
// libc++ in the pinned toolchain exposes shared_ptr atomic operations as free functions.
struct SharedSession {
    std::shared_ptr<Session> value;
    std::shared_ptr<Session> load() const { return std::atomic_load(&value); }
    void store(std::shared_ptr<Session> next) { std::atomic_store(&value, std::move(next)); }
};
struct Slot {
    std::mutex guard;
    SharedSession session;
};
std::array<Slot, MAX_SESSIONS> slots;
std::atomic<jlong> nextToken{0};
thread_local Session *invoking = nullptr;
std::mutex moduleGuard;
std::set<HMODULE> coreModules;
// No core call holds a process-wide session lock. A hung owner only retains its own slot.
using Lock = std::unique_lock<std::mutex>;
Lock moduleLock() {
    // Only protects the handle set, never held while executing/loading/unloading a DLL.
    return Lock(moduleGuard);
}
size_t slotIndex(jlong token) {
    if (token <= 0 || (token & 255) >= MAX_SESSIONS)
        throw std::runtime_error("Invalid JNI reservation");
    return static_cast<size_t>(token & 255);
}
struct Access {
    Lock lock;
    std::shared_ptr<Session> session;
    Session *previous;
    explicit Access(jlong token) : lock(slots[slotIndex(token)].guard, std::try_to_lock), previous(invoking) {
        if (!lock.owns_lock())
            throw std::runtime_error("JNI session is busy on its owner thread");
        session = slots[slotIndex(token)].session.load();
        if (!session || session->token != token || session->owner != GetCurrentThreadId())
            throw std::runtime_error("Invalid JNI session or owner thread");
        invoking = session.get();
    }
    ~Access() { invoking = previous; }
    Access(const Access &) = delete;
};
void release(Session &s) { slots[s.slot].session.store(nullptr); }
// Each core receives slot-specific callbacks. Wrong-thread callbacks cannot mutate another core.
struct CallbackScope {
    Session *previous = invoking;
    std::shared_ptr<Session> keep;
    explicit CallbackScope(size_t slot) {
        if (invoking && invoking->slot == slot && invoking->owner == GetCurrentThreadId()) return;
        keep = slots[slot].session.load();
        if (keep) keep->wrongThread = true;
        invoking = nullptr;
    }
    ~CallbackScope() { invoking = previous; }
};
struct Callbacks {
    retro_environment_t environment;
    retro_video_refresh_t video;
    retro_audio_sample_t sample;
    retro_audio_sample_batch_t batch;
    retro_input_poll_t poll;
    retro_input_state_t input;
    retro_hw_get_current_framebuffer_t framebuffer;
    retro_hw_get_proc_address_t proc;
};
const Callbacks &callbacks(size_t slot);
void fail(Session &s, const char *value) noexcept {
    if (!s.failure[0]) {
        size_t n = 0;
        while (n + 1 < sizeof(s.failure) && value[n]) {
            s.failure[n] = value[n];
            ++n;
        }
        s.failure[n] = 0;
    }
}
Session *current() noexcept {
    auto s = invoking;
    if (s && s->owner != GetCurrentThreadId()) {
        s->wrongThread = true;
        return nullptr;
    }
    return s;
}
Session &require(jlong token, bool ready = true) {
    auto s = invoking;
    if (!s || s->token != token || s->owner != GetCurrentThreadId())
        throw std::runtime_error("Invalid JNI session or owner thread");
    if (s->cleanupFailed)
        throw std::runtime_error("Native teardown failed; no further core calls allowed until JVM exit");
    if (ready && (!s->initialized || !s->loaded))
        throw std::runtime_error("JNI reservation has no loaded core");
    return *s;
}
void check(Session &s) {
    if (s.wrongThread)
        throw std::runtime_error("Core used an unsupported asynchronous callback thread");
    if (s.failure[0])
        throw std::runtime_error(s.failure);
}
void io(JNIEnv *env, const char *value) {
    if (!env->ExceptionCheck()) {
        auto type = env->FindClass("java/io/IOException");
        if (type)
            env->ThrowNew(type, value);
    }
}
std::wstring wide(JNIEnv *env, jstring value, size_t maximum = 4096) {
    if (!value)
        throw std::runtime_error("Missing string");
    jsize n = env->GetStringLength(value);
    if (n < 1 || static_cast<size_t>(n) > maximum)
        throw std::runtime_error("String length outside bounds");
    auto p = env->GetStringChars(value, nullptr);
    if (!p)
        throw std::runtime_error("Cannot access string");
    std::wstring result(reinterpret_cast<const wchar_t *>(p), n);
    env->ReleaseStringChars(value, p);
    if (result.find(L'\0') != std::wstring::npos)
        throw std::runtime_error("NUL in string");
    return result;
}
std::string utf8(const std::wstring &w) {
    int n = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, w.data(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    if (!n)
        throw std::runtime_error("Invalid Unicode string");
    std::string out(n, 0);
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, w.data(), (int)w.size(), out.data(), n, nullptr, nullptr);
    return out;
}
std::string text(const char *p, size_t maximum) {
    if (!p)
        throw std::runtime_error("Missing core metadata string");
    size_t n = 0;
    while (n < maximum && p[n])
        n++;
    if (n == maximum)
        throw std::runtime_error("Oversized core metadata string");
    return std::string(p, n);
}
std::filesystem::path checkedPath(JNIEnv *env, jstring value, bool directory) {
    auto p = std::filesystem::path(wide(env, value));
    if (!p.is_absolute() || p != p.lexically_normal())
        throw std::runtime_error("Native path must be absolute and normalized");
    auto q = p;
    while (!q.empty()) {
        DWORD a = GetFileAttributesW(q.c_str());
        if (a == INVALID_FILE_ATTRIBUTES || (a & FILE_ATTRIBUTE_REPARSE_POINT))
            throw std::runtime_error("Native path missing or contains a reparse point");
        if (q == p && bool(a & FILE_ATTRIBUTE_DIRECTORY) != directory)
            throw std::runtime_error("Native path type mismatch");
        auto parent = q.parent_path();
        if (parent == q)
            break;
        q = parent;
    }
    return p;
}
#include "runtime_dependencies.h"
void dimensions(unsigned w, unsigned h) {
    if (!w || !h || w > MAX_DIM || h > MAX_DIM)
        throw std::runtime_error("Video geometry exceeds 2048x2048");
}
void imageSize(Session &s, unsigned w, unsigned h) {
    dimensions(w, h);
    if (s.width == (int)w && s.height == (int)h && !s.pixels.empty())
        return;
    s.width = w;
    s.height = h;
    s.pixels.assign(size_t(w) * h * 4, 0);
    for (size_t i = 3; i < s.pixels.size(); i += 4)
        s.pixels[i] = 255;
}
void geometry(Session &s, const retro_game_geometry &g, bool maximum) {
    dimensions(g.base_width, g.base_height);
    if (maximum) {
        dimensions(g.max_width, g.max_height);
        if (g.max_width < g.base_width || g.max_height < g.base_height)
            throw std::runtime_error("Invalid maximum geometry");
        if (s.hardware && s.contextReset &&
            (g.max_width != (unsigned)s.maxWidth || g.max_height != (unsigned)s.maxHeight))
            throw std::runtime_error("Changing hardware maximum geometry requires restarting JNI");
        s.maxWidth = g.max_width;
        s.maxHeight = g.max_height;
    } else if (s.maxWidth && (g.base_width > (unsigned)s.maxWidth || g.base_height > (unsigned)s.maxHeight))
        throw std::runtime_error("Geometry exceeds declared maximum");
    double aspect = g.aspect_ratio == 0 ? double(g.base_width) / g.base_height : g.aspect_ratio;
    if (!std::isfinite(aspect) || aspect < .1 || aspect > 10)
        throw std::runtime_error("Aspect outside public frame limits");
    s.aspect = aspect;
    imageSize(s, g.base_width, g.base_height);
}
void av(Session &s, const retro_system_av_info &value) {
    geometry(s, value.geometry, true);
    double fps = value.timing.fps, rate = value.timing.sample_rate;
    if (!std::isfinite(fps) || fps < 1 || fps > 240 || !std::isfinite(rate) || rate < 8000 || rate > 192000)
        throw std::runtime_error("Core timing outside limits");
    s.fps = fps;
    s.sampleRate = rate;
}
void refreshAv(Session &s) {
    retro_system_av_info value{};
    s.retro_get_system_av_info(&value);
    av(s, value);
}
retro_proc_address_t proc(const char *name) {
    auto s = current();
    if (!s || !name || !s->context)
        return nullptr;
    PROC p = wglGetProcAddress(name);
    if (!p || p == (PROC)1 || p == (PROC)2 || p == (PROC)3 || p == (PROC)-1)
        p = GetProcAddress(s->gl, name);
    return reinterpret_cast<retro_proc_address_t>(p);
}
template <class T> T glfun(const char *name) {
    auto p = proc(name);
    if (!p)
        throw std::runtime_error(std::string("Missing OpenGL function: ") + name);
    return reinterpret_cast<T>(p);
}
uintptr_t framebuffer() {
    auto s = current();
    return s ? s->fbo : 0;
}
void createContext(Session &s) {
    if (wglGetCurrentContext())
        throw std::runtime_error("JNI owner already has an OpenGL context");
    s.gl = LoadLibraryExW(L"opengl32.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!s.gl)
        throw std::runtime_error("OpenGL unavailable");
    WNDCLASSW wc{};
    wc.lpfnWndProc = DefWindowProcW;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = s.windowClass.c_str();
    wc.style = CS_OWNDC;
    if (!RegisterClassW(&wc))
        throw std::runtime_error("Cannot register private WGL window");
    s.ownsClass = true;
    s.window = CreateWindowW(s.windowClass.c_str(), L"PIQ libretro JNI worker", WS_POPUP, 0, 0, 1, 1, nullptr, nullptr,
                             wc.hInstance, nullptr);
    if (!s.window)
        throw std::runtime_error("Cannot create private WGL window");
    s.dc = GetDC(s.window);
    if (!s.dc)
        throw std::runtime_error("Cannot acquire WGL DC");
    PIXELFORMATDESCRIPTOR p{};
    p.nSize = sizeof(p);
    p.nVersion = 1;
    p.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    p.iPixelType = PFD_TYPE_RGBA;
    p.cColorBits = 32;
    p.cDepthBits = 24;
    p.cStencilBits = 8;
    int f = ChoosePixelFormat(s.dc, &p);
    if (!f || !SetPixelFormat(s.dc, f, &p))
        throw std::runtime_error("OpenGL pixel format unavailable");
    s.context = wglCreateContext(s.dc);
    if (!s.context || !wglMakeCurrent(s.dc, s.context))
        throw std::runtime_error("WGL context unavailable");
    auto version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
    // Desktop GL starts with major.minor; do not accept an OpenGL ES prefix.
    if (!version || version[0] < '2' || version[0] > '9' || version[1] != '.' || version[2] < '0' || version[2] > '9' ||
        (version[0] == '2' && version[2] < '1'))
        throw std::runtime_error("OpenGL 2.1 compatibility support required");
}
void createFramebuffer(Session &s) {
    glfun<Gen>("glGenFramebuffers")(1, &s.fbo);
    s.bind = glfun<Bind>("glBindFramebuffer");
    s.bind(0x8D40, s.fbo);
    glGenTextures(1, &s.texture);
    glBindTexture(GL_TEXTURE_2D, s.texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, s.maxWidth, s.maxHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glfun<void(APIENTRY *)(GLenum, GLenum, GLenum, GLuint, GLint)>("glFramebufferTexture2D")(
        0x8D40, 0x8CE0, GL_TEXTURE_2D, s.texture, 0);
    if (s.hw.depth) {
        glfun<Gen>("glGenRenderbuffers")(1, &s.depth);
        glfun<Bind>("glBindRenderbuffer")(0x8D41, s.depth);
        glfun<void(APIENTRY *)(GLenum, GLenum, GLsizei, GLsizei)>("glRenderbufferStorage")(
            0x8D41, s.hw.stencil ? 0x88F0 : 0x81A6, s.maxWidth, s.maxHeight);
        glfun<void(APIENTRY *)(GLenum, GLenum, GLenum, GLuint)>("glFramebufferRenderbuffer")(
            0x8D40, s.hw.stencil ? 0x821A : 0x8D00, 0x8D41, s.depth);
    }
    if (glfun<GLenum(APIENTRY *)(GLenum)>("glCheckFramebufferStatus")(0x8D40) != 0x8CD5)
        throw std::runtime_error("Incomplete private framebuffer");
    glViewport(0, 0, s.width, s.height);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
}
void option(Session &s, const retro_variable &variable) {
    std::string key, body;
    std::vector<std::string> values;
    if (variable.value) {
        key = text(variable.key, 129);
        auto desc = text(variable.value, 8192);
        auto semi = desc.find(';');
        if (semi == std::string::npos)
            throw std::runtime_error("Malformed core option");
        body = desc.substr(semi + 1);
        while (!body.empty() && body[0] == ' ')
            body.erase(0, 1);
        size_t start = 0;
        for (;;) {
            auto end = body.find('|', start);
            values.push_back(body.substr(start, end == std::string::npos ? end : end - start));
            if (end == std::string::npos)
                break;
            start = end + 1;
        }
    } else {
        if (!(s.features & LEGACY_INLINE_OPTIONS))
            throw std::runtime_error(
                "Core uses nonstandard inline options; trusted profile compatibility flag required");
        // Explicit compatibility grammar: key; token|label token|label. Tokens cannot contain whitespace.
        // Do not infer game names or accept arbitrary undeclared pinned values.
        auto desc = text(variable.key, 8192);
        auto semi = desc.find(';');
        if (semi == std::string::npos)
            throw std::runtime_error("Malformed legacy inline option");
        key = desc.substr(0, semi);
        if (key.empty() || key.size() > 128 ||
            key.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-") !=
                std::string::npos)
            throw std::runtime_error("Invalid legacy inline option key");
        body = desc.substr(semi + 1);
        size_t at = 0;
        while ((at = body.find('|', at)) != std::string::npos) {
            auto start = body.find_last_of(" \t", at);
            start = start == std::string::npos ? 0 : start + 1;
            if (start == at || at + 1 == body.size())
                throw std::runtime_error("Invalid legacy inline option token");
            values.push_back(body.substr(start, at - start));
            at++;
        }
    }
    if (values.empty() || values.size() > 128)
        throw std::runtime_error("Core option count exceeds limit");
    for (const auto &value : values)
        if (value.empty() || value.size() > 512)
            throw std::runtime_error("Core option value exceeds limits");
    auto pin = s.pins.find(key);
    if (pin != s.pins.end() && std::find(values.begin(), values.end(), pin->second) == values.end())
        throw std::runtime_error("Unsupported pinned option value: " + key);
    if (s.offered.size() >= 256 && !s.offered.count(key))
        throw std::runtime_error("Too many core options");
    s.defaults[key] = values[0];
    s.offered[key] = std::move(values);
}
bool environment(unsigned cmd, void *data) {
    auto s = current();
    if (!s)
        return false;
    try {
        if (cmd == RETRO_ENVIRONMENT_SHUTDOWN) {
            s->shutdown = true;
            return true;
        }
        if (cmd == RETRO_ENVIRONMENT_GET_INPUT_BITMASKS)
            return true;
        if (!data)
            return false;
        switch (cmd) {
        case RETRO_ENVIRONMENT_SET_ROTATION: {
            unsigned n = *(unsigned *)data;
            if (n > 3)
                return false;
            s->rotation = (4 - n) & 3;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_OVERSCAN:
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *)data = true;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            s->supportsNoGame = *(bool *)data;
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **)data = s->system.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = s->save.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *(const char **)data = s->system.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
            *(const char **)data = s->corePath.c_str();
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            int n = *(retro_pixel_format *)data;
            if (n < 0 || n > 2)
                return false;
            s->pixelFormat = n;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            geometry(*s, *(retro_game_geometry *)data, false);
            return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            av(*s, *(retro_system_av_info *)data);
            return true;
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto r = (retro_hw_render_callback *)data;
            if (!(s->features & GL_COMPAT) || r->context_type != RETRO_HW_CONTEXT_OPENGL || !r->context_reset ||
                r->version_major > 2 || (r->version_major == 2 && r->version_minor > 1) || s->hardware)
                return false;
            r->get_current_framebuffer = callbacks(s->slot).framebuffer;
            r->get_proc_address = callbacks(s->slot).proc;
            s->hw = *r;
            s->hardware = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            if (!(s->features & GL_COMPAT))
                return false;
            *(retro_hw_context_type *)data = RETRO_HW_CONTEXT_OPENGL;
            return true;
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
            if (!(s->features & KEYBOARD))
                return false;
            s->keyboard = *(retro_keyboard_callback *)data;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES: {
            uint64_t bits = 1ULL << RETRO_DEVICE_JOYPAD;
            if (s->features & POINTER)
                bits |= 1ULL << RETRO_DEVICE_POINTER;
            if (s->features & MOUSE)
                bits |= 1ULL << RETRO_DEVICE_MOUSE;
            if (s->features & KEYBOARD)
                bits |= 1ULL << RETRO_DEVICE_KEYBOARD;
            *(uint64_t *)data = bits;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *(unsigned *)data = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *(unsigned *)data = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto v = (retro_variable *)data;
            auto key = text(v->key, 129);
            auto pin = s->pins.find(key), def = s->defaults.find(key);
            if (pin != s->pins.end()) {
                v->value = pin->second.c_str();
                return true;
            }
            if (def != s->defaults.end()) {
                v->value = def->second.c_str();
                return true;
            }
            v->value = nullptr;
            return false;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            auto variables = (retro_variable *)data;
            for (size_t i = 0; i <= 256; i++) {
                if (!variables[i].key)
                    return true;
                if (i == 256)
                    break;
                option(*s, variables[i]);
            }
            throw std::runtime_error("Too many core options");
        }
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
            return true;
        case RETRO_ENVIRONMENT_GET_GAME_INFO_EXT:
            if (s->features & NO_GAME)
                return false;
            *(const retro_game_info_ext **)data = &s->extended;
            return true;
        default:
            return false;
        }
    } catch (const std::exception &ex) {
        char message[512];
        std::snprintf(message, sizeof(message), "Environment callback %u: %.440s", cmd, ex.what());
        fail(*s, message);
        return false;
    } catch (...) {
        fail(*s, "Native environment callback failed");
        return false;
    }
}
void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    auto s = current();
    if (!s || !data)
        return;
    try {
        dimensions(w, h);
        if (s->maxWidth && (w > (unsigned)s->maxWidth || h > (unsigned)s->maxHeight))
            throw std::runtime_error("Video exceeds declared maximum geometry");
        imageSize(*s, w, h);
        if (data == RETRO_HW_FRAME_BUFFER_VALID) {
            if (!s->hardware || !s->contextReset)
                throw std::runtime_error("Unexpected hardware framebuffer");
        } else {
            if (s->hardware)
                throw std::runtime_error("Hardware core returned unsupported software frame");
            unsigned bytes = s->pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
            if (pitch < size_t(w) * bytes || pitch > MAX_DIM * 16 || pitch * h > 256ULL * 1024 * 1024)
                throw std::runtime_error("Video pitch exceeds limits");
            for (unsigned y = 0; y < h; y++)
                for (unsigned x = 0; x < w; x++) {
                    const auto p = (const uint8_t *)data + y * pitch + x * bytes;
                    unsigned value = 0, r, g, b;
                    std::memcpy(&value, p, bytes);
                    if (bytes == 4) {
                        r = (value >> 16) & 255;
                        g = (value >> 8) & 255;
                        b = value & 255;
                    } else {
                        unsigned bits = s->pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ? 6 : 5;
                        r = (value >> (5 + bits)) & 31;
                        g = (value >> 5) & ((1 << bits) - 1);
                        b = value & 31;
                        r = (r << 3) | (r >> 2);
                        g = bits == 6 ? (g << 2) | (g >> 4) : (g << 3) | (g >> 2);
                        b = (b << 3) | (b >> 2);
                    }
                    size_t i = (size_t(y) * w + x) * 4;
                    s->pixels[i] = static_cast<uint8_t>(r);
                    s->pixels[i + 1] = static_cast<uint8_t>(g);
                    s->pixels[i + 2] = static_cast<uint8_t>(b);
                    s->pixels[i + 3] = 255;
                }
        }
        s->duplicate = false;
    } catch (const std::exception &ex) {
        fail(*s, ex.what());
    } catch (...) {
        fail(*s, "Native video callback failed");
    }
}
void sample(int16_t a, int16_t b) {
    auto s = current();
    if (!s || !s->running)
        return;
    if (s->audio.size() + 2 > MAX_AUDIO) {
        fail(*s, "Audio output exceeds frame budget");
        return;
    }
    try {
        s->audio.push_back(a);
        s->audio.push_back(b);
    } catch (...) {
        fail(*s, "Audio allocation failed");
    }
}
size_t batch(const int16_t *data, size_t frames) {
    auto s = current();
    if (!s)
        return 0;
    if (frames > MAX_AUDIO / 2 || (!data && frames)) {
        fail(*s, "Audio callback exceeds frame budget");
        return 0;
    }
    if (!s->running)
        return frames;
    if (s->audio.size() + frames * 2 > MAX_AUDIO) {
        fail(*s, "Audio output exceeds frame budget");
        return 0;
    }
    try {
        if (frames)
            s->audio.insert(s->audio.end(), data, data + frames * 2);
        return frames;
    } catch (...) {
        fail(*s, "Audio allocation failed");
        return 0;
    }
}
void poll() {}
int16_t input(unsigned port, unsigned device, unsigned index, unsigned id) {
    auto s = current();
    if (!s || index)
        return 0;
    unsigned base = device & RETRO_DEVICE_MASK;
    if (base == RETRO_DEVICE_JOYPAD && port < s->devices.size() &&
        (s->devices[port] & RETRO_DEVICE_MASK) == RETRO_DEVICE_JOYPAD)
        return id == RETRO_DEVICE_ID_JOYPAD_MASK ? (int16_t)s->input[port] : id < 16 ? (s->input[port] >> id) & 1 : 0;
    if ((s->features & MESEN_GUN) && port == 0) {
        int gun = s->input[12];
        if (base == RETRO_DEVICE_POINTER && id == 0)
            return static_cast<int16_t>(((gun & 255) * 65536 / 256) + 128 - 32768);
        if (base == RETRO_DEVICE_POINTER && id == 1)
            return static_cast<int16_t>((((gun >> 8) & 255) * 65536 / 240) + 136 - 32768);
        if (base == RETRO_DEVICE_MOUSE && id == 2)
            return (gun >> 17) & 1;
        if (base == RETRO_DEVICE_MOUSE && id == 3)
            return (gun >> 16) & 1;
    }
    if (port == (unsigned)s->input[4]) {
        if (base == RETRO_DEVICE_POINTER && (s->features & POINTER)) {
            if (id == RETRO_DEVICE_ID_POINTER_X)
                return static_cast<int16_t>(s->input[5]);
            if (id == RETRO_DEVICE_ID_POINTER_Y)
                return static_cast<int16_t>(s->input[6]);
            if (id == RETRO_DEVICE_ID_POINTER_PRESSED)
                return s->input[7] & 1;
            if (id == RETRO_DEVICE_ID_POINTER_COUNT)
                return 1;
        }
        if (base == RETRO_DEVICE_MOUSE && (s->features & MOUSE)) {
            if (id == RETRO_DEVICE_ID_MOUSE_X)
                return static_cast<int16_t>(s->input[8]);
            if (id == RETRO_DEVICE_ID_MOUSE_Y)
                return static_cast<int16_t>(s->input[9]);
            if (id == RETRO_DEVICE_ID_MOUSE_LEFT)
                return s->input[7] & 1;
            if (id == RETRO_DEVICE_ID_MOUSE_RIGHT)
                return (s->input[7] >> 1) & 1;
            if (id == RETRO_DEVICE_ID_MOUSE_MIDDLE)
                return (s->input[7] >> 2) & 1;
            if (id == RETRO_DEVICE_ID_MOUSE_WHEELUP)
                return s->input[10] > 0;
            if (id == RETRO_DEVICE_ID_MOUSE_WHEELDOWN)
                return s->input[10] < 0;
        }
    }
    if (base == RETRO_DEVICE_KEYBOARD && (s->features & KEYBOARD) && port == 0 && id < s->keys.size())
        return s->keys[id];
    return 0;
}
bool allowedDevice(int d, int features) {
    int b = d & RETRO_DEVICE_MASK;
    return d == RETRO_DEVICE_NONE || (d >= 0 && d <= 65535 && b == RETRO_DEVICE_JOYPAD) ||
           (d == RETRO_DEVICE_POINTER && (features & POINTER)) || (d == RETRO_DEVICE_MOUSE && (features & MOUSE)) ||
           (d == RETRO_DEVICE_KEYBOARD && (features & KEYBOARD)) || (d == 262 && (features & MESEN_GUN));
}
template<size_t I> struct BoundCallbacks {
    static bool env(unsigned c, void *p) { CallbackScope scope(I); return environment(c, p); }
    static void pic(const void *p, unsigned w, unsigned h, size_t pitch) { CallbackScope scope(I); video(p,w,h,pitch); }
    static void sound(int16_t a, int16_t b) { CallbackScope scope(I); sample(a,b); }
    static size_t samples(const int16_t *p, size_t n) { CallbackScope scope(I); return batch(p,n); }
    static void polling() { CallbackScope scope(I); poll(); }
    static int16_t controls(unsigned p, unsigned d, unsigned i, unsigned b) { CallbackScope scope(I); return input(p,d,i,b); }
    static uintptr_t fbo() { CallbackScope scope(I); return framebuffer(); }
    static retro_proc_address_t address(const char *n) { CallbackScope scope(I); return proc(n); }
    static constexpr Callbacks table{env,pic,sound,samples,polling,controls,fbo,address};
};
const Callbacks &callbacks(size_t slot) {
    static constexpr std::array tables{BoundCallbacks<0>::table,BoundCallbacks<1>::table,
        BoundCallbacks<2>::table,BoundCallbacks<3>::table};
    return tables.at(slot);
}
void dispose(Session &s) {
    if (s.cleanupFailed)
        throw std::runtime_error("Native teardown previously failed; reservation retained until JVM exit");
    try {
        if (s.loaded) {
            s.retro_unload_game();
            s.loaded = false;
        }
        if (s.contextReset && s.hw.context_destroy) {
            s.hw.context_destroy();
            s.contextReset = false;
        }
        if (s.initialized) {
            s.retro_deinit();
            s.initialized = false;
        }
        if (s.core) {
            if (!FreeLibrary(s.core))
                throw std::runtime_error("Cannot unload native core; reservation retained");
            if (s.moduleRegistered) { auto module = moduleLock(); coreModules.erase(s.core); }
            s.moduleRegistered = false;
            s.core = nullptr;
        }
        if (s.context) {
            if (!wglMakeCurrent(nullptr, nullptr) || !wglDeleteContext(s.context))
                throw std::runtime_error("Cannot release WGL context; reservation retained");
            s.context = nullptr;
        }
        if (s.dc) {
            if (!ReleaseDC(s.window, s.dc))
                throw std::runtime_error("Cannot release WGL DC; reservation retained");
            s.dc = nullptr;
        }
        if (s.window) {
            if (!DestroyWindow(s.window))
                throw std::runtime_error("Cannot destroy WGL window; reservation retained");
            s.window = nullptr;
        }
        if (s.ownsClass) {
            if (!UnregisterClassW(s.windowClass.c_str(), GetModuleHandleW(nullptr)))
                throw std::runtime_error("Cannot unregister WGL window; reservation retained");
            s.ownsClass = false;
        }
        if (s.gl) {
            if (!FreeLibrary(s.gl))
                throw std::runtime_error("Cannot unload WGL library; reservation retained");
            s.gl = nullptr;
        }
    } catch (...) {
        s.cleanupFailed = true;
        throw;
    }
}
void metadata(JNIEnv *env, Session &s, jintArray ints, jdoubleArray doubles) {
    if (!ints || !doubles || env->GetArrayLength(ints) != 11 || env->GetArrayLength(doubles) != 3)
        throw std::runtime_error("Metadata buffer layout mismatch");
    jint values[] = {s.width,
                     s.height,
                     s.maxWidth,
                     s.maxHeight,
                     s.pixelFormat,
                     s.rotation,
                     (jint)s.pixels.size(),
                     (jint)s.audio.size(),
                     s.duplicate ? 1 : 0,
                     s.shutdown ? 1 : 0,
                     s.hardware ? 1 : 0};
    jdouble times[] = {s.aspect, s.fps, s.sampleRate};
    env->SetIntArrayRegion(ints, 0, 11, values);
    env->SetDoubleArrayRegion(doubles, 0, 3, times);
}
std::vector<uint8_t> bytes(JNIEnv *env, jbyteArray array, size_t max, bool empty) {
    if (!array)
        throw std::runtime_error("Missing byte array");
    jsize n = env->GetArrayLength(array);
    if (n < 0 || (!empty && !n) || size_t(n) > max)
        throw std::runtime_error("Byte array exceeds limit");
    std::vector<uint8_t> out(n);
    if (n)
        env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(out.data()));
    if (env->ExceptionCheck())
        throw std::runtime_error("Cannot read byte array");
    return out;
}
jbyteArray result(JNIEnv *env, const void *data, size_t n) {
    auto out = env->NewByteArray((jsize)n);
    if (out && n)
        env->SetByteArrayRegion(out, 0, (jsize)n, reinterpret_cast<const jbyte *>(data));
    return out;
}
size_t memoryLimit(int id) {
    if (id == 0)
        return MAX_RAM;
    if (id == 1)
        return MAX_RTC;
    if (id == 2 || id == 3)
        return MAX_STATE;
    throw std::runtime_error("Unsupported memory region");
}
} // namespace
#define JNI(name) Java_cn_piq_retro_libretro_jni_NativeLibretroBridge_##name
extern "C" JNIEXPORT jint JNICALL JNI(abiVersion)(JNIEnv *, jclass) { return ABI; }
extern "C" JNIEXPORT jint JNICALL JNI(runtimeDependencyApiVersion)(JNIEnv *, jclass) { return 1; }
extern "C" JNIEXPORT void JNICALL JNI(retainRuntimeDependencies)(JNIEnv *env,jclass,jobjectArray paths,jobjectArray hashes) {
    try { RuntimeDependencies::retain(env,paths,hashes); }
    catch(const std::exception &ex) { io(env,ex.what()); }
    catch(...) { io(env,"Runtime dependency initialization failed; restart required"); }
}
// Queries never acquire an owner's mutex or enter a core. A failed open retains its known token.
extern "C" JNIEXPORT jint JNICALL JNI(availableSlots)(JNIEnv *, jclass) {
    int free = 0;
    for (auto &slot : slots) if (!slot.session.load()) free++;
    return free;
}
extern "C" JNIEXPORT jboolean JNICALL JNI(reservationHeld)(JNIEnv *, jclass, jlong token) {
    if (token <= 0 || (token & 255) >= MAX_SESSIONS) return JNI_FALSE;
    auto s = slots[static_cast<size_t>(token & 255)].session.load();
    return s && s->token == token ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL JNI(reserve)(JNIEnv *env, jclass) {
    try {
        for (size_t i = 0; i < slots.size(); i++) {
            Lock lock(slots[i].guard, std::try_to_lock);
            if (!lock.owns_lock() || slots[i].session.load()) continue;
            auto s = std::make_shared<Session>();
            auto generation = ++nextToken;
            if (generation <= 0 || generation > INT64_MAX / 256)
                throw std::runtime_error("JNI token space exhausted; restart required");
            s->token = generation * 256 + static_cast<jlong>(i);
            s->slot = i;
            s->windowClass = L"PIQGenericLibretroJniAbi2_" + std::to_wstring(s->token);
            slots[i].session.store(s);
            return s->token;
        }
        throw std::runtime_error("JNI session limit reached (4); wait for an existing session to close");
    } catch (const std::exception &ex) { io(env, ex.what()); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL JNI(openReserved)(JNIEnv *env, jclass, jlong token, jstring dll, jstring content, jstring system,
                                             jstring saves, jstring expected, jboolean fullPath, jintArray ports,
                                             jobjectArray options, jint features) {
    Session *s = nullptr;
    std::unique_ptr<Access> access;
    try {
        access = std::make_unique<Access>(token);
        auto &reserved = require(token, false);
        if (reserved.core || reserved.initialized || reserved.loaded || !reserved.corePath.empty())
            throw std::runtime_error("JNI reservation already opened");
        s = &reserved;
        if (features < 0 || (features & ~ALL_FEATURES))
            throw std::runtime_error("Unsupported feature flags");
        bool noGame = (features & NO_GAME) != 0;
        auto core = checkedPath(env, dll, false), sys = checkedPath(env, system, true),
             save = checkedPath(env, saves, true);
        std::filesystem::path file;
        uintmax_t size = 0;
        if (noGame) {
            if (!content || env->GetStringLength(content) != 0)
                throw std::runtime_error("NO_GAME content path must be empty");
        } else {
            file = checkedPath(env, content, false);
            size = std::filesystem::file_size(file);
            if (!size)
                throw std::runtime_error("Native content file is empty");
            if (size > (fullPath ? MAX_FULLPATH_CONTENT : MAX_MEMORY_CONTENT))
                throw std::runtime_error(fullPath ? "Content exceeds 96 MiB native full-path limit"
                                                 : "Content exceeds 64 MiB native memory-content limit");
        }
        auto expectedName = utf8(wide(env, expected, 128));
        if (!ports || env->GetArrayLength(ports) < 1 || env->GetArrayLength(ports) > 4 || !options ||
            env->GetArrayLength(options) > 512 || env->GetArrayLength(options) % 2)
            throw std::runtime_error("Invalid profile arrays");
        s->features = features;
        s->input[4] = -1;
        s->input[5] = s->input[6] = -32768;
        s->input[11] = -1;
        s->input[12] = (features & MESEN_GUN) ? 1 << 16 : 0;
        s->system = utf8(sys.wstring());
        s->save = utf8(save.wstring());
        s->corePath = utf8(core.wstring());
        if (!noGame) {
            s->path = utf8(file.wstring());
            s->directory = utf8(file.parent_path().wstring());
            s->basename = utf8(file.stem().wstring());
            s->extension = utf8(file.extension().wstring());
            if (!s->extension.empty())
                s->extension.erase(0, 1);
            if (s->extension.empty() || s->extension.size() > 12 ||
                s->extension.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789") != std::string::npos)
                throw std::runtime_error("Invalid content extension");
        }
        jint devices[4]{};
        env->GetIntArrayRegion(ports, 0, env->GetArrayLength(ports), devices);
        if (env->ExceptionCheck())
            throw std::runtime_error("Cannot read devices");
        for (int i = 0; i < env->GetArrayLength(ports); i++) {
            if (!allowedDevice(devices[i], features))
                throw std::runtime_error("Device not declared by JNI capabilities");
            s->devices.push_back(devices[i]);
        }
        if ((features & MESEN_GUN) && (expectedName != "Mesen" || s->devices.size() != 2 || s->devices[1] != 262))
            throw std::runtime_error("Invalid legacy Mesen gun profile");
        for (int i = 0; i < env->GetArrayLength(options); i += 2) {
            auto k = (jstring)env->GetObjectArrayElement(options, i),
                 v = (jstring)env->GetObjectArrayElement(options, i + 1);
            auto key = utf8(wide(env, k, 128)), value = utf8(wide(env, v, 512));
            env->DeleteLocalRef(k);
            env->DeleteLocalRef(v);
            if (!s->pins.emplace(key, value).second)
                throw std::runtime_error("Duplicate pinned option");
        }
        // Persistent extended-content overrides may request bytes even for a full-path core.
        // Keep this allocation alive until unload: at most 96 MiB for an explicitly
        // full-path profile, 64 MiB otherwise. File-backed Java loading saves Java
        // heap copies; it does not remove this bounded native compatibility copy.
        if (!noGame) {
            s->content.resize(size);
            std::ifstream stream(file, std::ios::binary);
            if (!stream.read(reinterpret_cast<char *>(s->content.data()), size) ||
                stream.peek() != std::char_traits<char>::eof())
                throw std::runtime_error("Content changed while reading");
        }
        s->audio.reserve(MAX_AUDIO);
        // Recheck retained runtime identities for every new core, not only the
        // first Java bridge load. No module/global lock spans LoadLibrary.
        RuntimeDependencies::checkBeforeCoreOpen();
        s->core =
            LoadLibraryExW(core.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (!s->core)
            throw std::runtime_error("Core DLL could not be loaded");
        {
            auto module = moduleLock();
            if (!coreModules.insert(s->core).second)
                throw std::runtime_error("Core DLL instance is already loaded; stage a private copy per session");
            s->moduleRegistered = true;
        }
#define API(name)                                                                                                      \
    s->name = reinterpret_cast<decltype(s->name)>(GetProcAddress(s->core, #name));                                     \
    if (!s->name)                                                                                                      \
        throw std::runtime_error("Missing libretro export: " #name);
        API(retro_api_version)
        API(retro_set_environment)
        API(retro_set_video_refresh)
        API(retro_set_audio_sample) API(retro_set_audio_sample_batch) API(retro_set_input_poll)
            API(retro_set_input_state) API(retro_init) API(retro_get_system_info) API(retro_get_system_av_info)
                API(retro_load_game) API(retro_run) API(retro_reset) API(retro_unload_game) API(retro_deinit)
                    API(retro_set_controller_port_device) API(retro_serialize_size) API(retro_serialize)
                        API(retro_unserialize) API(retro_get_memory_data) API(retro_get_memory_size)
#undef API
                            if (s->retro_api_version() !=
                                RETRO_API_VERSION) throw std::runtime_error("Core libretro ABI mismatch");
        retro_system_info info{};
        s->retro_get_system_info(&info);
        s->name = text(info.library_name, 129);
        s->version = text(info.library_version, 129);
        if (s->name != expectedName || info.need_fullpath != bool(fullPath))
            throw std::runtime_error("Core name or content mode differs from trusted profile");
        if (!noGame) {
            auto extensions = "|" + text(info.valid_extensions, 2048) + "|";
            if (extensions.find("|" + s->extension + "|") == std::string::npos)
                throw std::runtime_error("Core does not accept content extension");
        }
        s->extended.full_path = s->path.c_str();
        s->extended.dir = s->directory.c_str();
        s->extended.name = s->basename.c_str();
        s->extended.ext = s->extension.c_str();
        s->extended.data = s->content.empty() ? nullptr : s->content.data();
        s->extended.size = s->content.size();
        s->extended.persistent_data = true;
        if (features & GL_COMPAT)
            createContext(*s);
        const auto &cb = callbacks(s->slot);
        s->retro_set_environment(cb.environment);
        s->retro_init();
        s->initialized = true;
        check(*s);
        s->retro_set_video_refresh(cb.video);
        s->retro_set_audio_sample(cb.sample);
        s->retro_set_audio_sample_batch(cb.batch);
        s->retro_set_input_poll(cb.poll);
        s->retro_set_input_state(cb.input);
        retro_game_info game{};
        game.path = s->path.c_str();
        if (!fullPath) {
            game.data = s->content.data();
            game.size = s->content.size();
        }
        if (noGame && !s->supportsNoGame)
            throw std::runtime_error("Core did not declare NO_GAME support");
        s->loaded = s->retro_load_game(noGame ? nullptr : &game);
        check(*s);
        if (!s->loaded)
            throw std::runtime_error("Core rejected content");
        for (auto &pin : s->pins) {
            auto at = s->offered.find(pin.first);
            if (at == s->offered.end() ||
                std::find(at->second.begin(), at->second.end(), pin.second) == at->second.end())
                throw std::runtime_error("Pinned option not offered by core: " + pin.first);
        }
        refreshAv(*s);
        if (s->hardware) {
            createFramebuffer(*s);
            s->contextReset = true;
            s->hw.context_reset();
            check(*s);
        }
        for (unsigned p = 0; p < 4; p++)
            s->retro_set_controller_port_device(p, p < s->devices.size() ? s->devices[p] : RETRO_DEVICE_NONE);
        if (features & MESEN_GUN)
            s->retro_set_controller_port_device(4, RETRO_DEVICE_NONE);
        check(*s);
        return s->token;
    } catch (const std::exception &ex) {
        if (s) {
            try {
                dispose(*s);
                release(*s);
            } catch (...) {
                io(env, "Native startup cleanup failed; session remains reserved until JVM exit");
                return 0;
            }
        }
        io(env, ex.what());
        return 0;
    } catch (...) {
        if (s) {
            try {
                dispose(*s);
                release(*s);
            } catch (...) {
                io(env, "Native startup cleanup failed; session remains reserved until JVM exit");
                return 0;
            }
        }
        io(env, "Native startup failed");
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL JNI(metadata)(JNIEnv *env, jclass, jlong token, jintArray ints,
                                                jdoubleArray doubles) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        metadata(env, s, ints, doubles);
    } catch (const std::exception &ex) {
        io(env, ex.what());
    }
}
extern "C" JNIEXPORT jstring JNICALL JNI(coreVersion)(JNIEnv *env, jclass, jlong token) {
    try {
        Access access(token);
        auto &s = require(token);
        return env->NewStringUTF(s.version.c_str());
    } catch (const std::exception &ex) {
        io(env, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jint JNICALL JNI(saveCapabilities)(JNIEnv *env, jclass, jlong token) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        size_t state = s.retro_serialize_size(), ram = s.retro_get_memory_size(0), rtc = s.retro_get_memory_size(1);
        // A number of legacy cores report a placeholder size but reject every
        // serialization. Probe without restoring or advancing the core; size
        // alone must not advertise usable save states.
        jint mask = 0;
        if (state > 0 && state <= MAX_STATE) {
            std::vector<uint8_t> probe(state);
            if (s.retro_serialize(probe.data(), probe.size()))
                mask |= 1;
        }
        if (ram <= MAX_RAM && rtc <= MAX_RTC && (ram || rtc) && (!ram || s.retro_get_memory_data(0)) &&
            (!rtc || s.retro_get_memory_data(1)))
            mask |= 2;
        check(s);
        return mask;
    } catch (const std::exception &ex) {
        io(env, ex.what());
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL JNI(step)(JNIEnv *env, jclass, jlong token, jobject pixels, jobject sound,
                                            jintArray controls, jintArray events, jintArray ints,
                                            jdoubleArray doubles) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        if (!pixels || !sound || !controls || !events || env->GetArrayLength(controls) != 13 ||
            env->GetArrayLength(events) > 512 || env->GetArrayLength(events) % 4 || !ints || !doubles ||
            env->GetArrayLength(ints) != 11 || env->GetArrayLength(doubles) != 3)
            throw std::runtime_error("Frame ABI layout mismatch");
        auto v = env->GetDirectBufferAddress(pixels), a = env->GetDirectBufferAddress(sound);
        auto vc = env->GetDirectBufferCapacity(pixels), ac = env->GetDirectBufferCapacity(sound);
        if (!v || !a || vc < jlong(s.maxWidth) * s.maxHeight * 4 || vc > MAX_VIDEO || ac != MAX_AUDIO * 2)
            throw std::runtime_error("Invalid direct output buffers");
        std::array<jint, 13> in{};
        std::array<jint, 512> keys{};
        int count = env->GetArrayLength(events);
        env->GetIntArrayRegion(controls, 0, 13, in.data());
        env->GetIntArrayRegion(events, 0, count, keys.data());
        if (env->ExceptionCheck())
            return;
        for (int i = 0; i < 4; i++)
            if (in[i] < 0 || in[i] > 65535 || (i >= (int)s.devices.size() && in[i]))
                throw std::runtime_error("Invalid pad input");
        if (in[4] < -1 || in[4] > 3 || in[5] < -32768 || in[5] > 32767 || in[6] < -32768 || in[6] > 32767 ||
            in[7] < 0 || in[7] > 7 || in[8] < -32768 || in[8] > 32767 || in[9] < -32768 || in[9] > 32767 ||
            in[10] < -1 || in[10] > 1)
            throw std::runtime_error("Invalid pointer input");
        if (in[4] >= 0 && !(s.features & (POINTER | MOUSE)))
            throw std::runtime_error("Pointer input not enabled");
        if (in[11] != -1 && ((in[11] != 0 && in[11] != 1 && in[11] != 2 && in[11] != 3 && in[11] != 6) ||
                             !allowedDevice(in[11], s.features)))
            throw std::runtime_error("Invalid port 0 override");
        if (s.features & MESEN_GUN) {
            if ((in[12] & ~0x3ffff) || ((in[12] >> 8) & 255) >= 240 || ((in[12] & (1 << 16)) && (in[12] & 65535)))
                throw std::runtime_error("Invalid Mesen gun input");
        } else if (in[12])
            throw std::runtime_error("Gun input not enabled");
        if (count && !(s.features & KEYBOARD))
            throw std::runtime_error("Keyboard not enabled");
        for (int i = 0; i < count; i += 4) {
            int c = keys[i + 2];
            if (keys[i] < 0 || keys[i] > 1 || keys[i + 1] < 0 || keys[i + 1] >= 512 || c < 0 || c > 0x10ffff ||
                (c >= 0xd800 && c <= 0xdfff) || keys[i + 3] < 0 || keys[i + 3] > 65535)
                throw std::runtime_error("Invalid keyboard event");
        }
        if (s.shutdown) {
            s.audio.clear();
            metadata(env, s, ints, doubles);
            return;
        }
        s.input = in;
        if (in[11] != -1 && s.devices[0] != (unsigned)in[11]) {
            s.devices[0] = in[11];
            s.retro_set_controller_port_device(0, in[11]);
        }
        for (int i = 0; i < count; i += 4) {
            s.keys[keys[i + 1]] = keys[i] != 0;
            if (s.keyboard.callback)
                s.keyboard.callback(keys[i] != 0, keys[i + 1], keys[i + 2], static_cast<uint16_t>(keys[i + 3]));
        }
        if (s.window) {
            MSG msg;
            unsigned n = 0;
            while (n++ < 256 && PeekMessageW(&msg, s.window, 0, 0, PM_REMOVE)) {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        s.audio.clear();
        s.duplicate = true;
        s.running = true;
        s.retro_run();
        s.running = false;
        check(s);
        if (s.hardware && !s.duplicate) {
            s.bind(0x8D40, s.fbo);
            glfun<Bind>("glBindBuffer")(0x88EB, 0);
            glReadBuffer(0x8CE0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SWAP_BYTES, GL_FALSE);
            glReadPixels(0, 0, s.width, s.height, GL_RGBA, GL_UNSIGNED_BYTE, s.pixels.data());
            if (glGetError() != GL_NO_ERROR)
                throw std::runtime_error("OpenGL frame readback failed");
            if (s.hw.bottom_left_origin) {
                size_t stride = size_t(s.width) * 4;
                for (int y = 0; y < s.height / 2; y++)
                    for (size_t x = 0; x < stride; x++)
                        std::swap(s.pixels[size_t(y) * stride + x], s.pixels[size_t(s.height - 1 - y) * stride + x]);
            }
            for (size_t i = 3; i < s.pixels.size(); i += 4)
                s.pixels[i] = 255;
        }
        if (vc < (jlong)s.pixels.size())
            throw std::runtime_error("Direct video buffer smaller than actual frame");
        std::memcpy(v, s.pixels.data(), s.pixels.size());
        if (!s.audio.empty())
            std::memcpy(a, s.audio.data(), s.audio.size() * 2);
        metadata(env, s, ints, doubles);
    } catch (const std::exception &ex) {
        io(env, ex.what());
    } catch (...) {
        io(env, "Native step failed");
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI(serialize)(JNIEnv *env, jclass, jlong token) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        size_t n = s.retro_serialize_size();
        if (!n || n > MAX_STATE)
            throw std::runtime_error("State unavailable or exceeds 16 MiB");
        std::vector<uint8_t> out(n);
        if (!s.retro_serialize(out.data(), n))
            throw std::runtime_error("Core rejected serialization");
        check(s);
        return result(env, out.data(), n);
    } catch (const std::exception &ex) {
        io(env, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT void JNICALL JNI(restore)(JNIEnv *env, jclass, jlong token, jbyteArray state) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        auto data = bytes(env, state, MAX_STATE, false);
        if (data.size() != s.retro_serialize_size())
            throw std::runtime_error("State/core size mismatch");
        if (!s.retro_unserialize(data.data(), data.size()))
            throw std::runtime_error("Core rejected state");
        s.audio.clear();
        check(s);
        refreshAv(s);
    } catch (const std::exception &ex) {
        io(env, ex.what());
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI(memory)(JNIEnv *env, jclass, jlong token, jint id) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        auto limit = memoryLimit(id), n = s.retro_get_memory_size(id);
        if (n > limit)
            throw std::runtime_error("Core memory region exceeds budget");
        auto data = s.retro_get_memory_data(id);
        if (n && !data)
            throw std::runtime_error("Core memory region missing");
        check(s);
        return result(env, data, n);
    } catch (const std::exception &ex) {
        io(env, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT void JNICALL JNI(restoreMemory)(JNIEnv *env, jclass, jlong token, jbyteArray ram, jbyteArray rtc) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        auto a = bytes(env, ram, MAX_RAM, true), b = bytes(env, rtc, MAX_RTC, true);
        size_t an = s.retro_get_memory_size(0), bn = s.retro_get_memory_size(1);
        auto ap = s.retro_get_memory_data(0), bp = s.retro_get_memory_data(1);
        if (an != a.size() || bn != b.size() || (an && !ap) || (bn && !bp))
            throw std::runtime_error("Persistent memory size or pointer mismatch");
        check(s);
        if (an)
            std::memcpy(ap, a.data(), an);
        if (bn)
            std::memcpy(bp, b.data(), bn);
    } catch (const std::exception &ex) {
        io(env, ex.what());
    }
}
extern "C" JNIEXPORT void JNICALL JNI(reset)(JNIEnv *env, jclass, jlong token) {
    try {
        Access access(token);
        auto &s = require(token);
        check(s);
        s.retro_reset();
        s.audio.clear();
        s.keys.fill(false);
        check(s);
        refreshAv(s);
    } catch (const std::exception &ex) {
        io(env, ex.what());
    }
}
extern "C" JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jclass, jlong token) {
    try {
        Access access(token);
        auto &s = require(token, false);
        dispose(s);
        release(s);
    } catch (const std::exception &ex) {
        io(env, ex.what());
    }
}
