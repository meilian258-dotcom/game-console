// SPDX-License-Identifier: GPL-3.0-or-later
// Synthetic test DLL only. Marker proves whether its entry point was reached.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#ifndef PIQ_RUNTIME_REJECT_ATTACH
#define PIQ_RUNTIME_REJECT_ATTACH 0
#endif
extern "C" __declspec(dllexport) int runtime_mock_value() { return 7; }
BOOL WINAPI DllMain(HINSTANCE,DWORD reason,LPVOID) {
    if(reason!=DLL_PROCESS_ATTACH) return TRUE;
    wchar_t path[4096]{};
    DWORD length=GetEnvironmentVariableW(L"PIQ_RUNTIME_TEST_MARKER",path,4096);
    if(!length || length>=4096) return FALSE;
    HANDLE marker=CreateFileW(path,FILE_APPEND_DATA,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(marker==INVALID_HANDLE_VALUE) return FALSE;
    char byte=PIQ_RUNTIME_REJECT_ATTACH?'F':'L'; DWORD written=0;
    BOOL ok=WriteFile(marker,&byte,1,&written,nullptr);CloseHandle(marker);
    // A bounded pause makes competing first callers observable without joining
    // threads or calling JVM functions from under the loader lock.
    Sleep(200);
    return ok && written==1 && !PIQ_RUNTIME_REJECT_ATTACH;
}
