// SPDX-License-Identifier: GPL-3.0-or-later
// Private implementation included inside the bridge's anonymous namespace.
// Fixed trusted Java manifests supply hashes. This is not a native-code sandbox.
namespace RuntimeDependencies {
constexpr size_t MAX_BYTES = 16 * 1024 * 1024, MAX_MODULES = 2048, MAX_DIRECTORIES = 64;
enum State { Empty, Initializing, Ready, Failed };
struct Identity { ULONGLONG volume = 0; FILE_ID_128 id{}; std::wstring finalPath; };
struct Library {
    std::filesystem::path path;
    std::wstring name;
    std::string sha;
    HANDLE file = INVALID_HANDLE_VALUE;
    HMODULE module = nullptr;
    Identity identity;
};
// Raw owned handles intentionally survive DLL/session teardown. Only process exit
// releases these bounded dependencies. Never add a destructor that unloads them.
std::array<Library, 2> libraries;
std::array<HANDLE, MAX_DIRECTORIES> directories{};
size_t libraryCount = 0, directoryCount = 0;
std::atomic<State> state{Empty};

bool equalPath(const std::wstring &a, const std::wstring &b) {
    return CompareStringOrdinal(a.c_str(), static_cast<int>(a.size()), b.c_str(),
                                static_cast<int>(b.size()), TRUE) == CSTR_EQUAL;
}
Identity identity(HANDLE file) {
    FILE_ID_INFO info{};
    if (!GetFileInformationByHandleEx(file, FileIdInfo, &info, sizeof(info)))
        throw std::runtime_error("Cannot identify runtime file");
    std::vector<wchar_t> path(512);
    for (;;) {
        DWORD n = GetFinalPathNameByHandleW(file, path.data(), static_cast<DWORD>(path.size()),
                                           FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
        if (!n || n > 4096) throw std::runtime_error("Cannot resolve runtime file path");
        if (n < path.size()) return {info.VolumeSerialNumber, info.FileId, std::wstring(path.data(), n)};
        path.resize(static_cast<size_t>(n) + 1);
    }
}
bool sameIdentity(const Identity &a, const Identity &b) {
    return a.volume == b.volume && !std::memcmp(&a.id, &b.id, sizeof(a.id)) && equalPath(a.finalPath,b.finalPath);
}
std::wstring modulePath(HMODULE module) {
    std::vector<wchar_t> path(512);
    for (;;) {
        DWORD n = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
        if (!n || n > 4096) throw std::runtime_error("Cannot resolve loaded runtime path");
        if (n < path.size()) return std::wstring(path.data(), n);
        if (path.size() >= 4097) throw std::runtime_error("Loaded runtime path exceeds bound");
        path.resize(std::min<size_t>(4097,path.size()*2));
    }
}
void verifyModule(const Library &library) {
    auto path = modulePath(library.module);
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                               FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    if (file == INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot inspect loaded runtime identity");
    try {
        if (!sameIdentity(identity(file),library.identity))
            throw std::runtime_error("Loaded runtime differs from the locked file");
    } catch (...) { CloseHandle(file); throw; }
    CloseHandle(file);
}
void checkModules() {
    std::array<HMODULE,MAX_MODULES> modules{};
    DWORD needed = 0;
    if (!K32EnumProcessModulesEx(GetCurrentProcess(),modules.data(),sizeof(modules),&needed,LIST_MODULES_ALL)
        || needed > sizeof(modules) || needed % sizeof(HMODULE))
        throw std::runtime_error("Cannot inspect bounded runtime module list");
    std::array<size_t,2> seen{};
    for (size_t n=0;n<needed/sizeof(HMODULE);++n) {
        auto name = std::filesystem::path(modulePath(modules[n])).filename().wstring();
        for (size_t i=0;i<libraryCount;++i) {
            if (!equalPath(name,libraries[i].name)) continue;
            if (!libraries[i].module || modules[n]!=libraries[i].module || ++seen[i]!=1)
                throw std::runtime_error("Conflicting runtime DLL already loaded; restart required");
            verifyModule(libraries[i]);
        }
    }
    for (size_t i=0;i<libraryCount;++i)
        if (libraries[i].module && seen[i]!=1) throw std::runtime_error("Owned runtime module disappeared");
}
void checkBeforeCoreOpen() {
    auto current=state.load(std::memory_order_acquire);
    if(current==Empty) return; // Legacy isolated native callers have no retained bundle.
    if(current!=Ready) throw std::runtime_error("Runtime dependencies unavailable before core open; restart required");
    try {
        checkModules();
        if(state.load(std::memory_order_acquire)!=Ready)
            throw std::runtime_error("Runtime dependencies changed before core open; restart required");
    } catch(...) {
        state.store(Failed,std::memory_order_release);
        throw;
    }
}
void lockDirectories(const std::filesystem::path &parent) {
    for (auto path=parent;!path.empty();) {
        if (directoryCount==directories.size()) throw std::runtime_error("Runtime directory depth exceeds bound");
        HANDLE handle=CreateFileW(path.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,
                                  OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        if (handle==INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot retain runtime directory identity");
        directories[directoryCount++]=handle;
        FILE_ATTRIBUTE_TAG_INFO attributes{};
        if (!GetFileInformationByHandleEx(handle,FileAttributeTagInfo,&attributes,sizeof(attributes))
            || !(attributes.FileAttributes&FILE_ATTRIBUTE_DIRECTORY)
            || (attributes.FileAttributes&FILE_ATTRIBUTE_REPARSE_POINT))
            throw std::runtime_error("Runtime directory changed or is a reparse point");
        (void)identity(handle);
        auto next=path.parent_path();
        if (next==path) break;
        path=next;
    }
}
std::vector<uint8_t> readLocked(Library &library) {
    library.file=CreateFileW(library.path.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,
                              FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_SEQUENTIAL_SCAN,nullptr);
    if (library.file==INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot lock runtime file against writes");
    FILE_ATTRIBUTE_TAG_INFO attributes{}; LARGE_INTEGER size{};
    if (!GetFileInformationByHandleEx(library.file,FileAttributeTagInfo,&attributes,sizeof(attributes))
        || (attributes.FileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))
        || !GetFileSizeEx(library.file,&size) || size.QuadPart<=0 || size.QuadPart>static_cast<LONGLONG>(MAX_BYTES))
        throw std::runtime_error("Runtime file type or size is invalid");
    library.identity=identity(library.file);
    std::vector<uint8_t> bytes(static_cast<size_t>(size.QuadPart));
    DWORD read=0;
    if (!ReadFile(library.file,bytes.data(),static_cast<DWORD>(bytes.size()),&read,nullptr) || read!=bytes.size())
        throw std::runtime_error("Cannot read the complete locked runtime file");
    return bytes;
}
std::string sha256(const std::vector<uint8_t> &bytes) {
    BCRYPT_ALG_HANDLE algorithm=nullptr; BCRYPT_HASH_HANDLE hash=nullptr;
    // CNG retains this backing storage until BCryptDestroyHash, including errors.
    std::vector<uint8_t> object;
    auto close=[&] { if(hash) BCryptDestroyHash(hash); if(algorithm) BCryptCloseAlgorithmProvider(algorithm,0); };
    try {
        DWORD length=0,returned=0;
        if (BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0
            || BCryptGetProperty(algorithm,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&length),sizeof(length),&returned,0)<0
            || returned!=sizeof(length) || !length || length>65536)
            throw std::runtime_error("Cannot initialize runtime SHA-256");
        object.resize(length); std::array<uint8_t,32> digest{};
        if (BCryptCreateHash(algorithm,&hash,object.data(),length,nullptr,0,0)<0
            || BCryptHashData(hash,const_cast<PUCHAR>(bytes.data()),static_cast<ULONG>(bytes.size()),0)<0
            || BCryptFinishHash(hash,digest.data(),static_cast<ULONG>(digest.size()),0)<0)
            throw std::runtime_error("Cannot hash locked runtime file");
        static constexpr char hex[]="0123456789ABCDEF";
        std::string result; result.reserve(64);
        for(auto value:digest) { result+=hex[value>>4]; result+=hex[value&15]; }
        close(); return result;
    } catch (...) { close(); throw; }
}
struct Pe {
    const std::vector<uint8_t> &bytes;
    IMAGE_OPTIONAL_HEADER64 optional{};
    std::vector<IMAGE_SECTION_HEADER> sections;
    template<class T> T at(uint64_t offset) const {
        if (offset>bytes.size() || sizeof(T)>bytes.size()-offset) throw std::runtime_error("Truncated runtime PE");
        T result{}; std::memcpy(&result,bytes.data()+offset,sizeof(T)); return result;
    }
    explicit Pe(const std::vector<uint8_t> &input):bytes(input) {
        auto dos=at<IMAGE_DOS_HEADER>(0);
        if(dos.e_magic!=IMAGE_DOS_SIGNATURE || dos.e_lfanew<0) throw std::runtime_error("Invalid runtime DOS header");
        uint64_t offset=static_cast<uint32_t>(dos.e_lfanew);
        if(at<DWORD>(offset)!=IMAGE_NT_SIGNATURE) throw std::runtime_error("Invalid runtime PE signature");
        auto file=at<IMAGE_FILE_HEADER>(offset+4);
        if(file.Machine!=IMAGE_FILE_MACHINE_AMD64 || !(file.Characteristics&IMAGE_FILE_DLL)
            || !file.NumberOfSections || file.NumberOfSections>96 || file.SizeOfOptionalHeader!=sizeof(optional))
            throw std::runtime_error("Runtime must be a bounded AMD64 DLL");
        optional=at<IMAGE_OPTIONAL_HEADER64>(offset+4+sizeof(file));
        if(optional.Magic!=IMAGE_NT_OPTIONAL_HDR64_MAGIC || optional.NumberOfRvaAndSizes>IMAGE_NUMBEROF_DIRECTORY_ENTRIES
            || optional.SizeOfHeaders>bytes.size()) throw std::runtime_error("Invalid runtime optional header");
        offset+=4+sizeof(file)+file.SizeOfOptionalHeader;
        for(size_t i=0;i<file.NumberOfSections;++i) {
            auto section=at<IMAGE_SECTION_HEADER>(offset+i*sizeof(IMAGE_SECTION_HEADER));
            if(uint64_t(section.PointerToRawData)+section.SizeOfRawData>bytes.size())
                throw std::runtime_error("Runtime section exceeds file");
            sections.push_back(section);
        }
    }
    uint64_t rva(uint64_t address,size_t size) const {
        if(address>UINT32_MAX || size>UINT32_MAX-address) throw std::runtime_error("Runtime RVA overflow");
        size_t matches=0; uint64_t offset=0;
        if(address<optional.SizeOfHeaders && size<=optional.SizeOfHeaders-address) { ++matches; offset=address; }
        for(const auto &section:sections) {
            if(address>=section.VirtualAddress && address-section.VirtualAddress<section.SizeOfRawData
                && size<=section.SizeOfRawData-(address-section.VirtualAddress)) {
                ++matches;offset=section.PointerToRawData+(address-section.VirtualAddress);
            }
        }
        if(matches!=1 || offset>bytes.size() || size>bytes.size()-offset) throw std::runtime_error("Runtime RVA is not unique file data");
        return offset;
    }
    std::string name(uint32_t address) const {
        std::string result;
        for(size_t i=0;i<128;++i) {
            auto c=at<uint8_t>(rva(uint64_t(address)+i,1));
            if(!c) { if(result.empty()) break; return result; }
            if(c>='A' && c<='Z') c+=('a'-'A');
            if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c=='-'||c=='+'))
                throw std::runtime_error("Runtime import is not a plain ASCII basename");
            result+=static_cast<char>(c);
        }
        throw std::runtime_error("Runtime import name exceeds bound");
    }
    void allowed(const std::string &name,size_t current) const {
        static constexpr const char *system[]={"kernel32.dll","api-ms-win-crt-convert-l1-1-0.dll",
            "api-ms-win-crt-environment-l1-1-0.dll","api-ms-win-crt-filesystem-l1-1-0.dll",
            "api-ms-win-crt-heap-l1-1-0.dll","api-ms-win-crt-locale-l1-1-0.dll","api-ms-win-crt-math-l1-1-0.dll",
            "api-ms-win-crt-multibyte-l1-1-0.dll","api-ms-win-crt-private-l1-1-0.dll",
            "api-ms-win-crt-runtime-l1-1-0.dll","api-ms-win-crt-stdio-l1-1-0.dll",
            "api-ms-win-crt-string-l1-1-0.dll","api-ms-win-crt-time-l1-1-0.dll","api-ms-win-crt-utility-l1-1-0.dll"};
        for(auto value:system) if(name==value) return;
        for(size_t i=0;i<current;++i) if(name==utf8(libraries[i].name)) return;
        throw std::runtime_error("Runtime import is outside the fixed dependency closure");
    }
    void imports(size_t index,size_t current,bool delay) const {
        if(index>=optional.NumberOfRvaAndSizes) return;
        auto directory=optional.DataDirectory[index];
        if(!directory.VirtualAddress && !directory.Size) return;
        size_t width=delay?32:sizeof(IMAGE_IMPORT_DESCRIPTOR);
        if(!directory.VirtualAddress || directory.Size<width || directory.Size>65536)
            throw std::runtime_error("Runtime import directory exceeds bounds");
        for(uint64_t offset=0;offset+width<=directory.Size;offset+=width) {
            auto address=rva(uint64_t(directory.VirtualAddress)+offset,width);
            if(delay) {
                auto item=at<std::array<DWORD,8>>(address);
                if(std::all_of(item.begin(),item.end(),[](DWORD n){return n==0;})) return;
                if(item[0]!=1) throw std::runtime_error("Unsupported delay-import address encoding");
                allowed(name(item[1]),current);
            } else {
                auto item=at<IMAGE_IMPORT_DESCRIPTOR>(address); IMAGE_IMPORT_DESCRIPTOR zero{};
                if(!std::memcmp(&item,&zero,sizeof(item))) return;
                allowed(name(item.Name),current);
            }
        }
        throw std::runtime_error("Runtime import directory lacks a bounded terminator");
    }
};
void retain(JNIEnv *env,jobjectArray paths,jobjectArray hashes) {
    State expected=Empty;
    bool initializing=state.compare_exchange_strong(expected,Initializing,std::memory_order_acq_rel);
    if(!initializing && expected!=Ready) throw std::runtime_error("Runtime dependency initialization unavailable; restart required");
    try {
        if(!paths || !hashes || env->GetArrayLength(paths)<1 || env->GetArrayLength(paths)>2
            || env->GetArrayLength(paths)!=env->GetArrayLength(hashes)) throw std::runtime_error("Invalid runtime dependency arrays");
        size_t count=static_cast<size_t>(env->GetArrayLength(paths));
        std::array<std::filesystem::path,2> checked;
        std::array<std::string,2> expectedHashes;
        for(size_t i=0;i<count;++i) {
            auto path=static_cast<jstring>(env->GetObjectArrayElement(paths,static_cast<jsize>(i)));
            auto hash=static_cast<jstring>(env->GetObjectArrayElement(hashes,static_cast<jsize>(i)));
            try { checked[i]=checkedPath(env,path,false);expectedHashes[i]=utf8(wide(env,hash,64)); }
            catch(...) { env->DeleteLocalRef(path);env->DeleteLocalRef(hash);throw; }
            env->DeleteLocalRef(path);env->DeleteLocalRef(hash);
            auto name=checked[i].filename().wstring(), drive=checked[i].root_name().wstring();
            if((name!=L"libc++.dll" && name!=L"libunwind.dll") || drive.size()!=2 || drive[1]!=L':'
                || !((drive[0]>=L'A'&&drive[0]<=L'Z')||(drive[0]>=L'a'&&drive[0]<=L'z'))
                || expectedHashes[i].size()!=64 || expectedHashes[i].find_first_not_of("0123456789ABCDEFabcdef")!=std::string::npos)
                throw std::runtime_error("Runtime name, local path or SHA-256 is invalid");
            for(auto &c:expectedHashes[i]) if(c>='a'&&c<='f') c-=('a'-'A');
            if(i && (!equalPath(checked[0].parent_path().wstring(),checked[i].parent_path().wstring())
                     || name==checked[0].filename().wstring())) throw std::runtime_error("Runtime files must be unique in one owned directory");
        }
        if(!initializing) {
            if(count!=libraryCount) throw std::runtime_error("Runtime dependency set is already fixed");
            for(size_t i=0;i<count;++i)
                if(!equalPath(checked[i].wstring(),libraries[i].path.wstring()) || expectedHashes[i]!=libraries[i].sha)
                    throw std::runtime_error("Runtime dependency identity is already fixed");
            checkModules(); return;
        }
        libraryCount=count;
        for(size_t i=0;i<count;++i) { libraries[i].path=checked[i];libraries[i].name=checked[i].filename().wstring();libraries[i].sha=expectedHashes[i]; }
        lockDirectories(checked[0].parent_path());
        // Validate every locked file before any DLL entry point can run.
        for(size_t i=0;i<count;++i) {
            auto bytes=readLocked(libraries[i]);
            if(sha256(bytes)!=libraries[i].sha) throw std::runtime_error("Runtime dependency SHA-256 mismatch");
            Pe image(bytes); image.imports(IMAGE_DIRECTORY_ENTRY_IMPORT,i,false);image.imports(IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT,i,true);
        }
        checkModules();
        for(size_t i=0;i<count;++i) {
            libraries[i].module=LoadLibraryExW(libraries[i].path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);
            if(!libraries[i].module) throw std::runtime_error("Cannot load fixed runtime dependency; restart required");
            verifyModule(libraries[i]);checkModules();
        }
        state.store(Ready,std::memory_order_release);
    } catch(...) {
        state.store(Failed,std::memory_order_release);
        throw;
    }
}
} // namespace RuntimeDependencies
