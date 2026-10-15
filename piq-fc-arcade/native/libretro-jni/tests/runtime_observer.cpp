// SPDX-License-Identifier: GPL-3.0-or-later
// Test-only read-only module observer, not part of the shipped bridge.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <string>
extern "C" JNIEXPORT jstring JNICALL Java_cn_piq_retro_libretro_jni_RuntimeDependencyProbe_modulePath(JNIEnv *env,jclass,jstring name) {
    if(!name)return nullptr;
    auto chars=env->GetStringChars(name,nullptr);if(!chars)return nullptr;
    std::wstring value(reinterpret_cast<const wchar_t *>(chars),env->GetStringLength(name));
    env->ReleaseStringChars(name,chars);
    HMODULE module=GetModuleHandleW(value.c_str());wchar_t path[4096]{};
    DWORD length=module?GetModuleFileNameW(module,path,4096):0;
    if(length>=4096)return nullptr;
    return env->NewString(reinterpret_cast<const jchar *>(path),static_cast<jsize>(length));
}
