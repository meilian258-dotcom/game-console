# LLVM Windows runtime attribution

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
