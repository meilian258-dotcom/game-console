# Windows JNI bridge and LLVM runtime attribution

The bundled `libc++.dll` is the unmodified MSYS2 CLANG64 package
`mingw-w64-clang-x86_64-libc++` version `22.1.8-1` (LLVM 22.1.8).
It includes LLVM libc++, libc++abi and statically linked unwinder components.
The bridge itself remains statically linked; it owns a normal Windows module
reference to this shared runtime for the process lifetime. Emulator cores are
not pinned and continue to unload independently.

Binary identity: 1,659,392 bytes; SHA-256
`7344daed05388589e9bd691ed1d30c568c374da4b8b6a12e1502185948c03cd4`.
The cache filename `libcxx.dll` is only a build-cache alias; packaged and loaded
filename is `libc++.dll`. The file bytes are unchanged.

Sources and packaging:

- [LLVM 22.1.8 source](https://github.com/llvm/llvm-project/tree/llvmorg-22.1.8)
- [MSYS2 libc++ package](https://packages.msys2.org/package/mingw-w64-clang-x86_64-libc%2B%2B)
- [MSYS2 packaging recipe](https://github.com/msys2/MINGW-packages/tree/master/mingw-w64-libc%2B%2B)

The full license texts accompany this notice:

- `libcxx-LICENSE.txt`: original verified MSYS2 libc++ package license.
- `libunwind-LICENSE.txt`: original verified MSYS2 libunwind package license.
- `libcxxabi-LICENSE.txt`: upstream `llvmorg-22.1.8/libcxxabi/LICENSE.TXT`.

These contain the applicable Apache License 2.0 with LLVM exceptions and legacy
copyright, license and disclaimer notices. This attribution file does not
replace or modify those texts. `libunwind.dll` is not bundled separately by the
current one-dependency manifest.

## Static bridge runtime supplement

The fixed Windows x64 `piq-libretro-jni.dll` is 1,320,960 bytes, SHA-256
`fc731f3b83b0b23f473b48bb9f399582ace264c0646eec45fbffafaf0fb36923`.
Its recorded Clang build uses the static-runtime link route. Its own
compiler/CRT notice inventory is distinct from the shared `libc++.dll`
attribution above.

The following additional originals are preserved without editing:

- `compiler-rt-LICENSE.txt`: from the verified MSYS2 CLANG64
  `mingw-w64-clang-x86_64-compiler-rt` package `22.1.8-3`; the fixed
  upstream text is [LLVM 22.1.8 compiler-rt
  LICENSE.TXT](https://github.com/llvm/llvm-project/blob/llvmorg-22.1.8/compiler-rt/LICENSE.TXT).
- `MinGW-w64-v14.0.0-COPYING`,
  `MinGW-w64-v14.0.0-runtime-notices.txt`, and
  `MinGW-w64-v14.0.0-project-notices.txt`: the three original CRT notices
  from the verified MSYS2 CLANG64 CRT package
  `14.0.0.r426.g4564ee4b5-1`. Their bytes also match the fixed upstream
  MinGW-w64 v14.0.0 [COPYING](https://github.com/mingw-w64/mingw-w64/blob/v14.0.0/COPYING),
  [runtime notices](https://github.com/mingw-w64/mingw-w64/blob/v14.0.0/COPYING.MinGW-w64-runtime/COPYING.MinGW-w64-runtime.txt)
  and [project notices](https://github.com/mingw-w64/mingw-w64/blob/v14.0.0/COPYING.MinGW-w64/COPYING.MinGW-w64.txt).

The existing fixed toolchain package versions, URLs and archive digests
are recorded in
`piq-native-arcade/native/mame-lifecycle/ci-clang64-toolchain-lock.json`.
The bridge's complete PIQ implementation and build commands remain in
`piq-fc-arcade/native/libretro-jni/`; the toolchain inventory is not a claim
that every installed library was linked into the bridge.

All original exceptions and alternative-license provisions remain in
force. In particular, the LLVM exception includes permissions for code
embedded in compiled objects; this inventory does not create a new duty
to provide the compiler's complete source or imply that an omitted
standalone notice necessarily defeats that exception. Compiler-rt's
original legacy notices are retained rather than replaced by a generic
Apache license file.

No claim is made here that winpthreads is linked into this bridge solely
because it was installed in the compiler environment. Emulator-core
runtime inventories remain separate. These supplementary texts do not
modify a frozen DLL/JAR or certify that an older JAR already includes the
new files; retain the appropriate companion notices in the actual
distribution.
