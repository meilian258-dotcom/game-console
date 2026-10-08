// SPDX-License-Identifier: GPL-3.0-or-later
// Standalone Windows test: no JNI, core, or LoadLibrary calls are made by main.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <cstdlib>
#include <cstdio>
#include <new>

namespace HashTrace {
void *backing = nullptr, *quarantine = nullptr;
unsigned tick = 0, destroyed = 0, released = 0, destroyCalls = 0;
int failure = 0; // 0: real SHA, 1: HashData fails, 2: FinishHash fails.
void dispose(void *p) noexcept {
    if (p && p == backing) {
        released = ++tick; quarantine = p;
        return; // Keep memory valid even when exercising the old lifetime bug.
    }
    std::free(p);
}
}
void *operator new(std::size_t n) {
    if (auto p = std::malloc(n ? n : 1)) return p;
    throw std::bad_alloc();
}
void *operator new[](std::size_t n) { return ::operator new(n); }
void operator delete(void *p) noexcept { HashTrace::dispose(p); }
void operator delete[](void *p) noexcept { HashTrace::dispose(p); }
void operator delete(void *p, std::size_t) noexcept { HashTrace::dispose(p); }
void operator delete[](void *p, std::size_t) noexcept { HashTrace::dispose(p); }

NTSTATUS WINAPI testCreateHash(BCRYPT_ALG_HANDLE a, BCRYPT_HASH_HANDLE *h,
    PUCHAR object, ULONG size, PUCHAR secret, ULONG secretSize, ULONG flags) {
    auto status = BCryptCreateHash(a, h, object, size, secret, secretSize, flags);
    if (status >= 0) HashTrace::backing = object;
    return status;
}
NTSTATUS WINAPI testHashData(BCRYPT_HASH_HANDLE h, PUCHAR input, ULONG size, ULONG flags) {
    return HashTrace::failure == 1 ? NTSTATUS(-1) : BCryptHashData(h, input, size, flags);
}
NTSTATUS WINAPI testFinishHash(BCRYPT_HASH_HANDLE h, PUCHAR output, ULONG size, ULONG flags) {
    return HashTrace::failure == 2 ? NTSTATUS(-1) : BCryptFinishHash(h, output, size, flags);
}
NTSTATUS WINAPI testDestroyHash(BCRYPT_HASH_HANDLE h) {
    ++HashTrace::destroyCalls; HashTrace::destroyed = ++HashTrace::tick;
    return BCryptDestroyHash(h);
}
#define BCryptCreateHash testCreateHash
#define BCryptHashData testHashData
#define BCryptFinishHash testFinishHash
#define BCryptDestroyHash testDestroyHash
#include "../piq_libretro_jni.cpp"
#undef BCryptCreateHash
#undef BCryptHashData
#undef BCryptFinishHash
#undef BCryptDestroyHash

namespace Validation {
using Bytes = std::vector<uint8_t>;
constexpr size_t kCoff = 0x84, kOptional = 0x98, kSection = 0x188;
unsigned passed = 0;
void require(bool ok, const char *what) { if (!ok) throw std::runtime_error(what); }
template<class T> void put(Bytes &b, size_t offset, const T &value) {
    require(offset <= b.size() && sizeof(T) <= b.size() - offset, "Invalid test fixture write");
    std::memcpy(b.data() + offset, &value, sizeof(value));
}
void importName(Bytes &b, const char *name) {
    require(std::strlen(name) < 128, "Invalid test import length");
    std::memset(b.data() + 0x300, 0, 128);
    std::memcpy(b.data() + 0x300, name, std::strlen(name));
}
Bytes fixture() {
    Bytes b(0x800);
    IMAGE_DOS_HEADER dos{}; dos.e_magic = IMAGE_DOS_SIGNATURE; dos.e_lfanew = 0x80;
    put(b, 0, dos); put(b, 0x80, DWORD(IMAGE_NT_SIGNATURE));
    IMAGE_FILE_HEADER file{};
    file.Machine = IMAGE_FILE_MACHINE_AMD64; file.NumberOfSections = 1;
    file.SizeOfOptionalHeader = sizeof(IMAGE_OPTIONAL_HEADER64);
    file.Characteristics = IMAGE_FILE_DLL | IMAGE_FILE_EXECUTABLE_IMAGE;
    put(b, kCoff, file);
    IMAGE_OPTIONAL_HEADER64 optional{}; optional.Magic = IMAGE_NT_OPTIONAL_HDR64_MAGIC;
    optional.SizeOfHeaders = 0x200; optional.SizeOfImage = 0x2000;
    optional.SectionAlignment = 0x1000; optional.FileAlignment = 0x200;
    optional.NumberOfRvaAndSizes = IMAGE_NUMBEROF_DIRECTORY_ENTRIES;
    optional.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT] = {0x1000, 40};
    optional.DataDirectory[IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT] = {0x1200, 64};
    put(b, kOptional, optional);
    IMAGE_SECTION_HEADER section{}; section.VirtualAddress = 0x1000;
    section.Misc.VirtualSize = 0x600; section.SizeOfRawData = 0x600; section.PointerToRawData = 0x200;
    put(b, kSection, section);
    IMAGE_IMPORT_DESCRIPTOR item{}; item.Name = 0x1100; item.FirstThunk = 0x1180;
    put(b, 0x200, item);
    put(b, 0x400, std::array<DWORD, 8>{1, 0x1100, 0, 0x1180, 0x1180, 0, 0, 0});
    importName(b, "KERNEL32.DLL");
    return b;
}
void parse(const Bytes &b, size_t current = 0) {
    RuntimeDependencies::Pe image(b);
    image.imports(IMAGE_DIRECTORY_ENTRY_IMPORT, current, false);
    image.imports(IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT, current, true);
}
template<class Change> void reject(const char *label, Change change) {
    auto b = fixture(); change(b); bool rejected = false;
    try { parse(b); } catch (const std::runtime_error &) { rejected = true; }
    require(rejected, label); ++passed;
}
template<class Change> void optional(Bytes &b, Change change) {
    auto value = RuntimeDependencies::Pe(b).optional; change(value); put(b, kOptional, value);
}
template<class Change> void section(Bytes &b, Change change) {
    auto value = RuntimeDependencies::Pe(b).sections[0]; change(value); put(b, kSection, value);
}
void peTests() {
    parse(fixture()); ++passed;
    reject("Truncated DOS header accepted", [](auto &b) { b.resize(32); });
    reject("Bad DOS signature accepted", [](auto &b) { put(b, 0, WORD(0)); });
    reject("Bad PE signature accepted", [](auto &b) { put(b, 0x80, DWORD(0)); });
    reject("Truncated COFF header accepted", [](auto &b) { b.resize(0x90); });
    reject("Truncated optional header accepted", [](auto &b) { b.resize(0xa0); });
    reject("Negative PE offset accepted", [](auto &b) { put(b, 0x3c, LONG(-1)); });
    reject("Outside PE offset accepted", [](auto &b) { put(b, 0x3c, LONG(0x7fffffff)); });
    reject("Wrong machine accepted", [](auto &b) { put(b, kCoff, WORD(IMAGE_FILE_MACHINE_I386)); });
    reject("Non-DLL accepted", [](auto &b) { put(b, kCoff + 18, WORD(IMAGE_FILE_EXECUTABLE_IMAGE)); });
    reject("Short optional header accepted", [](auto &b) { put(b, kCoff + 16, WORD(2)); });
    reject("PE32 accepted", [](auto &b) { put(b, kOptional, WORD(IMAGE_NT_OPTIONAL_HDR32_MAGIC)); });
    reject("Too many directories accepted", [](auto &b) { optional(b, [](auto &o) { o.NumberOfRvaAndSizes = 17; }); });
    reject("Raw extent overflow accepted", [](auto &b) { section(b, [](auto &s) { s.PointerToRawData = UINT32_MAX; }); });
    reject("Zero-fill name accepted", [](auto &b) { section(b, [](auto &s) { s.SizeOfRawData = 0x80; }); });
    reject("Overlapping RVA accepted", [](auto &b) {
        auto s = RuntimeDependencies::Pe(b).sections[0]; put(b, kSection + sizeof(s), s); put(b, kCoff + 2, WORD(2));
    });
    reject("Import RVA overflow accepted", [](auto &b) { optional(b, [](auto &o) { o.DataDirectory[1].VirtualAddress = 0xfffffff0; }); });
    reject("Null import RVA accepted", [](auto &b) { optional(b, [](auto &o) { o.DataDirectory[1].VirtualAddress = 0; }); });
    reject("Missing import terminator accepted", [](auto &b) { optional(b, [](auto &o) { o.DataDirectory[1].Size = 20; }); });
    reject("Missing delay terminator accepted", [](auto &b) { optional(b, [](auto &o) { o.DataDirectory[13].Size = 32; }); });
    reject("Delay VA encoding accepted", [](auto &b) { put(b, 0x400, DWORD(0)); });
    reject("Unknown delay flags accepted", [](auto &b) { put(b, 0x400, DWORD(3)); });
    reject("Empty name accepted", [](auto &b) { importName(b, ""); });
    reject("Path import accepted", [](auto &b) { importName(b, "../kernel32.dll"); });
    reject("Unknown basename accepted", [](auto &b) { importName(b, "unlisted.dll"); });
    reject("Non-ASCII name accepted", [](auto &b) { b[0x300] = 0x80; });
    reject("Unterminated name accepted", [](auto &b) { std::memset(b.data() + 0x300, 'a', 128); });
    reject("Name beyond raw bytes accepted", [](auto &b) { put(b, 0x200 + 12, DWORD(0x15ff)); b[0x7ff] = 'a'; });
    reject("Delay-only bad import accepted", [](auto &b) {
        optional(b, [](auto &o) { o.DataDirectory[1] = {}; }); importName(b, "bad.dll");
    });
    RuntimeDependencies::libraries[0].name = L"libunwind.dll";
    auto b = fixture(); importName(b, "libunwind.dll"); parse(b, 1); ++passed;
    reject("Forward/self runtime accepted", [](auto &v) { importName(v, "libunwind.dll"); });
    RuntimeDependencies::libraries[0].name.clear();
}
void hashTest(int failure) {
    HashTrace::tick = HashTrace::destroyed = HashTrace::released = HashTrace::destroyCalls = 0;
    HashTrace::failure = failure; bool rejected = false; std::string digest;
    try { digest = RuntimeDependencies::sha256(Bytes{'a', 'b', 'c'}); }
    catch (const std::runtime_error &) { rejected = true; }
    bool order = HashTrace::backing && HashTrace::quarantine && HashTrace::destroyCalls == 1
        && HashTrace::destroyed > 0 && HashTrace::destroyed < HashTrace::released;
    void *retired = HashTrace::quarantine; HashTrace::backing = HashTrace::quarantine = nullptr;
    std::free(retired);
    require(order, "Hash backing storage released before BCryptDestroyHash");
    require(rejected == (failure != 0), "SHA failure injection was not propagated");
    if (!failure) require(digest == "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", "SHA-256 mismatch");
    ++passed;
}
}
int main() {
    try {
        Validation::peTests();
        for (int failure = 0; failure != 3; ++failure) Validation::hashTest(failure);
        std::printf("RUNTIME_VALIDATION_OK tests=%u\n", Validation::passed); return 0;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "RUNTIME_VALIDATION_FAILED: %s\n", error.what()); return 1;
    }
}
