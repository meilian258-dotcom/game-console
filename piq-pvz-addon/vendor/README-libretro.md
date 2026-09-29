# 植物大战僵尸 · libretro 核心

把 [PvZ-Portable](https://github.com/wszqkzqk/PvZ-Portable)（《植物大战僵尸》GOTY 版的跨平台重实现）
移植成 libretro 核心。核心本身**不含任何游戏素材**，`main.pak` 与 `properties/` 由玩家自行提供。

---

## 1. 快速开始（RetroArch）

```
dist/
├── cores/
│   ├── pvz_libretro.dll
│   └── pvz_libretro.info        -> 与 DLL 放一起（RetroArch「核心信息」用）
└── system/pvz/                 -> 复制到 RetroArch 的 system/
    ├── main.pak
    └── properties/
```

1. `dist/cores/` 里的**两个文件**（`pvz_libretro.dll` 和 `pvz_libretro.info`）→ `<RetroArch>/cores/`
2. `dist/system/pvz/` 整个文件夹 → `<RetroArch>/system/`
3. 启动 RetroArch，两种方式任选：

   * **不带内容启动**：`加载核心` → 选 `PvZ` → 返回主菜单 → `启动核心`。
     核心会去 `system/pvz/` 读 `main.pak` 和 `properties/`（即“BIOS”）。
   * **带内容启动**：`加载内容` → 选任意一个 `main.pak`。
     核心会用该文件所在目录作为资源目录，并在**同目录**下寻找 `properties/`；
     此模式下**完全不会读取 BIOS**。

---

## 2. 数据加载规则

| 模式 | 触发方式 | 资源目录 | properties/ 来源 | 是否用 BIOS |
| :-- | :-- | :-- | :-- | :-- |
| **BIOS 模式** | 不带内容启动核心 | `system/pvz/` | `system/pvz/properties/` | 是 |
| **内容模式** | 把 `main.pak` 当内容加载 | `main.pak` 所在目录 | 与该 `main.pak` **同目录**的 `properties/` | **否** |

细节：

* “资源目录”必须含有 `main.pak`；`properties/` 是可选的。
  缺少 `properties/` 时引擎会退回内置的英文字符串表，游戏仍可运行。
* 内容模式下若所选文件不是 `main.pak`、且其所在目录也没有 `main.pak`，
  核心会拒绝加载并通过 OSD / 日志给出明确原因，而不会静默黑屏。
* BIOS 模式下找不到 `system/pvz/main.pak` 时同样给出明确提示。
* 存档（用户配置、进度、缓存、截图）写到 libretro 的 **save 目录**：
  `<save>/PvZ-Portable/`。

---

## 3. 操作

游戏本身是点选式操作，因此指针（鼠标）是首选输入。

| 输入 | 作用 |
| :-- | :-- |
| 鼠标移动 / `RETRO_DEVICE_POINTER` | 移动光标 |
| 鼠标左键 / 指针按下 | 左键点击 |
| 鼠标右键 | 右键点击 |
| 滚轮 | 滚轮 |
| 手柄 左摇杆 / 十字键 | 移动虚拟光标 |
| 手柄 A | 左键点击 |
| 手柄 B | 右键点击 |
| 手柄 Start | Escape（菜单） |
| 手柄 Select | Enter |
| 手柄 L / R | 滚轮上 / 下 |
| 键盘 | 直接透传（角色名输入、热键） |

> 手柄玩家的虚拟光标：只要没有指针设备输入，左摇杆/十字键就会驱动一个核心内部的虚拟光标。

### 3.1 角色名（首次启动）

首次进入游戏会出现 “NEW USER / Please enter your name”，并且**必须填一个非空的名字**
才能建立存档档案（空名字会弹错误框，且没有其它出路）。输入走两条路：

1. 前端通过 `RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK` 推送的按键与字符事件（首选）；
2. 核心每帧轮询一次 `RETRO_DEVICE_KEYBOARD` 原始按键状态，把前端**没有转发**的按键
   连同它本该输入的字符一起补上。

第 2 条是必要的：RetroArch 在 “Game Focus Mode 关闭” 时会把**绑定到手柄按键或热键的
按键**吃掉，根本不转给核心（`input_auto_game_focus` 默认就是 `off`）。默认键位里
`A / S / X / Z`、`Q / W`、`E`（慢动作热键）、`R`（倒带热键）、方向键、回车都在此列
—— 只靠第 1 条的话，输入 `Player` 会变成 `Ply`。轮询之后无需任何设置即可正常输入；
下面的说明只用于排查。

* **输入没反应 / 少字**：打开 Game Focus Mode（`设置 → 输入 → Game Focus Mode`，
  默认热键 Scroll Lock），或把配置里的 `input_auto_game_focus` 改为 `"detect"`
  —— 核心注册键盘回调时正是按这个语义声明的。
* **屏幕键盘（overlay OSK）**：RetroArch 的 OSK 只产生按键状态、不产生字符，
  现在由核心的轮询补出字符，因此可以正常用来输入名字（需要先在
  `设置 → 屏幕键盘` 里指定 OSK overlay）。
* **完全没有键盘的前端**：若前端在 `RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES`
  里没有报告 `RETRO_DEVICE_KEYBOARD`，核心会把名字预填为 `Player` 并整体选中
  —— 直接点 OK 即可开始，有键盘时打字会覆盖它。
* 编辑键（Backspace / Delete / Home / End / 方向键 / 回车确认）与手柄光标点击均可用。
* 轮询是按帧进行的：被前端吞掉的按键，如果在同一帧内就按下又抬起（不足 16 ms，正常打
  字速度不会出现）才会丢字；正常速度输入不受影响。

### 3.2 即时存读档（Save / Load State）

用前端的存档功能即可（RetroArch 默认 `F2` 存档、`F4` 读档），每个存档位一份。

* **存的是「当前这一关」的完整快照**：植物、僵尸、子弹、金币、小推车、粒子、复活动画、
  拖尾、光标、种子栏、种子卡、挑战状态、波次计数、随机种子…… 也就是游戏「保存并退出」
  时写进 `userdata/game*.v4` 的那份数据，这里改成留在内存里，所以不碰磁盘、即存即读。
* **存档时机**：只能在关卡进行中。主菜单、图鉴、商店、禅境花园、关卡开场动画里存档会被
  拒绝（前端会显示存档失败）—— 这些状态要么没有快照，要么不该被回滚。
* **读档**：关卡中直接读回；在主菜单、关卡开场（种子选择 / 开场动画）、胜利结算界面读档
  会自动收拾掉这些界面并重建棋盘再进入关卡，所以「死了之后读档」「开场选植物时读档」都可用。
* **音乐**：读档时若曲目没变（关卡内存档 → 读档），混音器不动，BGM 不中断；若曲目变了
  （例如从种子选择界面读档），会重启为快照里该有的那首曲子。播放进度本身不保存 ——
  解码流在 SDL_mixer 里，不属于棋盘快照。
* **不会回滚玩家档案**：金币、关卡进度、成就、磁盘上的存档文件都不受存档位影响 ——
  存档位只管关卡，既不能刷金币也不会丢进度。
* **不支持倒带 / 前瞻**：一份快照通常只有几十 KB（实测一级关卡的中断存档约 28 KB），
  但前端的倒带缓冲是逐帧保存的，代价仍然不划算，核心也没有为逐帧快照做优化。
  核心向声明的大小上限是 1 MiB。
* 读档后音乐从头播放；关卡内的画面会在下一帧生效（前端暂停时按下的读档也一样）。

---

## 4. 从源码构建

### 4.1 依赖

| 组件 | 说明 |
| :-- | :-- |
| MinGW-w64 gcc/g++（C++20） | MSYS2 提供的 `mingw64` 或 `ucrt64` 环境，需含 SDL2 / zlib / libpng / libjpeg |
| CMake | Visual Studio 自带的即可 |
| mingw32-make | `pacman -S mingw-w64-x86_64-make` |
| libopenmpt 源码包 | 仅 BGM 需要，见 4.3 |

`tools\_find_toolchain.bat` 会自动从常见安装位置找到 MSYS2 与 CMake。
装在别处时用环境变量覆盖：

```bat
set MSYS=C:\path\to\mingw64
set VSCMAKE=C:\path\to\cmake\bin
```

### 4.2 构建

```bat
tools\build_libretro.bat Release            :: 默认，不含 libopenmpt
tools\build_libretro.bat Release --openmpt  :: 链接 libopenmpt，MO3 音乐完整
```

产物：`build-libretro\pvz_libretro.dll`。

CMake 也可直接用：

```bat
cmake -G "MinGW Makefiles" -S PvZ-Portable -B build-libretro ^
      -DCMAKE_BUILD_TYPE=Release ^
      -DCMAKE_C_COMPILER=D:/msys64/mingw64/bin/gcc.exe ^
      -DCMAKE_CXX_COMPILER=D:/msys64/mingw64/bin/g++.exe ^
      -DCMAKE_PREFIX_PATH=D:/msys64/mingw64 ^
      -DBUILD_STATIC=ON -DLIBRETRO=ON
cmake --build build-libretro --parallel
```

* `-DBUILD_STATIC=ON` 会把 SDL2 / zlib / libpng / libjpeg / libmodplug 以及
  libstdc++、libgcc、libwinpthread 全部静态链接，产出**只依赖 Windows 系统 DLL**
  的单文件核心（约 13 MB），可直接丢进 `cores/`。
* 不加 `BUILD_STATIC` 时核心会依赖 MSYS2 的若干 DLL，需要一并复制到 `cores/`。

### 4.3 libopenmpt（BGM / MO3 音乐）

游戏的主背景音乐是 `sounds/mainmusic.mo3` —— 一个 30 轨 tracker，按鼓/踩镲分轨做动态混音。
**只有 libopenmpt 同时注册 MO3 并实现分轨接口**（`Mix_ModMusicStreamSetChannelVolume` / `GetOrder`）。
libmodplug 完全读不了这个文件（实测 `ModPlug_Load` 返回 NULL）。

libopenmpt 官方只提供 autotools 构建和 **MSVC 预编译 DLL**，前者需要 POSIX shell，
后者无法链进 MinGW 编译的核心，所以本仓库直接编译它的源码：

```bat
tools\build_libopenmpt.bat            :: 解包 + 生成源码清单 + 编译 + 安装到 third_party\libopenmpt
tools\build_libretro.bat Release --openmpt
```

产物：`third_party\libopenmpt\lib\libopenmpt.a`（约 10 MB）+ `include\libopenmpt\`。

要点：

* 需要把 `libopenmpt-<版本>+release.autotools.tar.gz` 放在仓库根目录或 `third_party\`，
  脚本会自动解包；也可以自己解到 `third_party\libopenmpt-src\`。
* libopenmpt 要求 **C++20**，源码清单由 `tools\gen_libopenmpt_sources.ps1` 从
  `Makefile.am` 抽取（约 150 个 .cpp）。
* 必须带 `MPT_WITH_ZLIB`（MO3 容器解压）和 `MPT_WITH_VORBIS` + `MPT_WITH_VORBISFILE`
  （**MO3 里的采样是 Ogg Vorbis 压缩的**）。少了 Ogg 解码器，模块能打开、
  时长和轨数都对，但**每一个采样都是静音**，只在日志里留一句
  `Some compressed samples could not be loaded because they use an unsupported codec.`。
* 这两条对 C 源码也有影响：mixer 的 `.c` 会 include libopenmpt 的头，
  所以核心构建里 `CMAKE_C_STANDARD` 提到了 C11。

### 4.4 核心体积

核心只编游戏真正需要的解码器，其余全部关掉：

| 项 | 说明 |
| :-- | :-- |
| **libmodplug 关闭** | 游戏数据里只有 `.ogg` 音效和 `.mo3` 音乐，而 libmodplug **读不了 MO3**（实测 `ModPlug_Load` 返回 NULL）。开着它等于白白多带 21 种 tracker 格式，且启用 libopenmpt 后完全重复。 |
| **仅保留 WAV / OGG(stb_vorbis) / MP3(dr_mp3) / MO3(openmpt)** | WAV 内建；OGG、MP3 用 SDL-Mixer-X 自带的 stb_vorbis / dr_mp3，无需外部库；MO3 走 libopenmpt。FLAC / WavPack / GME / mpg123 / Opus / XMP / MIDI 全关。 |
| `-ffunction-sections -fdata-sections -Wl,--gc-sections` | 核心只用 libopenmpt 的 C API，C++ API 和扩展 API 会被链接器丢掉。libopenmpt 静态库也带同样的编译选项，否则 gc-sections 对它无效。 |
| `-s`（Release） | 去掉 COFF 符号表；`retro_*` 导出在 `.edata`，不受影响。 |
| libopenmpt 用 `-Os` | 它是体积大头（约 10 MB 代码）。解码 30 轨 MO3 只占实时预算的约 1.7%，为体积换一点速度很划算。 |

实测（MinGW，Release，含 BGM）：

| 配置 | 大小 |
| :-- | ---: |
| 初始（libmodplug + libopenmpt，未 strip） | 17.0 MB |
| 去掉 libmodplug + gc-sections + strip | 13.0 MB |
| 再把 libopenmpt 改成 `-Os` | **11.5 MB** |

> 注意：libopenmpt 的 `libopenmpt-small` 目标**不是**裁剪格式的精简版 ——
> 它的源文件列表和完整版完全一样（149 个编译单元），只是改用内建编解码器。
> 所以想再小就只能靠 LTO 或进一步裁剪游戏侧代码，没有"关掉某些格式"的开关。

---

## 5. 移植实现说明

### 5.1 新增文件

```
PvZ-Portable/src/SexyAppFramework/platform/libretro/
├── libretro.h              # 从 libretro 上游引入的 API 头
├── LibretroBackend.h       # 前后端共享状态与接口
├── LibretroBackend.cpp     # 前端回调、硬件渲染、音频桥、输入翻译
├── Window.cpp              # SexyAppBase::MakeWindow()：没有窗口
├── Input.cpp               # InitInput / ProcessDeferredMessages / 文本输入
└── Core.cpp                # retro_* 入口 + BIOS/内容解析
```

### 5.2 对上游源码的最小改动（全部在 `__LIBRETRO__` 之后）

| 文件 | 改动 |
| :-- | :-- |
| `graphics/GLPlatform.h` | `PlatformGLInit()` 改为经前端 `get_proc_address` 解析 GL 入口 |
| `graphics/GLInterface.{h,cpp}` | 新增 `GfxReapplyState()`；`UpdateViewport()` 固定整帧；`Flush()` 不交换缓冲、不清屏 |
| `SexyAppFramework/SexyAppBase.{h,cpp}` | `DoMainLoop()` 变空实现；`Start()` 收尾段跳过；3 处 `nanosleep` 关闭；`DoExit` 不 `exit()`；`EnforceCursor`/`ResetCustomCursorCache` 不碰 SDL 光标；`Popup` 改为日志 + OSD；新增 `LibretroTeardown()` |
| `sound/SDLSoundManager.cpp` | `Mix_OpenAudio` → `Mix_InitMixer`；析构 `Mix_FreeMixer`；`Initialized()` 不再依赖 SDL 音频子系统 |
| `CMakeLists.txt` | 新增 `LIBRETRO` / `LIBRETRO_OPENMPT` 选项与核心目标 |

### 5.3 三个关键设计

**渲染 —— 硬件 GL。**
引擎的渲染器就是 OpenGL ES 2.0，并且直接画进默认帧缓冲。因此核心通过
`RETRO_ENVIRONMENT_SET_HW_RENDER` 先要 GLES2、失败再要桌面 GL 2.1，
把画面渲染进前端给的 FBO。`bottom_left_origin = true`（游戏的 y 向下正交投影
在 GL 里天然是左上原点）。每帧开头重新绑定 FBO 并调 `GfxReapplyState()`
恢复程序/VBO/顶点属性/混合状态，因为前端可能在两次 `retro_run()` 之间改动 GL 状态。

> 评估过纯软件帧缓冲方案：`MemoryImage` 缺少 `BltMirror` / `StretchBltMirror`
> 实现，`FastStretchBlt` 的着色分支是空的，会导致镜像僵尸不可见、着色拉伸消失，
> 因此不采用。

**音频 —— 无需音频设备。**
SDL-Mixer-X 提供了 “自带输出” 接口：用 `Mix_InitMixer(&spec, SDL_TRUE)` 带一个
显式的 `SDL_AudioSpec`（44100 / AUDIO_S16SYS / 2ch）初始化混音器，再用
`Mix_GetGeneralMixer()` 取回混音函数，每次 `retro_run()` 同步混出 735 帧
（44100/60）交给 `audio_batch_cb`。没有 SDL 音频设备，也没有音频线程，完全确定性。
装载线程还在跑的时候输出静音，避免与解码竞争。

**时序 —— 前端驱动。**
游戏是固定 100 Hz 逻辑步（`mFrameTime = 10`）+ `SDL_GetTicks()` 累加器。
`retro_run()` 复刻了 Emscripten 的 rAF 回调：反复 `UpdateAppStep()` 直到
`UPDATESTATE_PROCESS_DONE` 且没有待绘制帧，保证**每次 `retro_run()` 正好画一帧**、
逻辑按真实时间推进。`av_info` 报 60 fps / 44100 Hz。

---

## 6. 自检

```bat
tools\smoke\run.bat
```

会编译一个很小的 libretro 驱动（`tools\smoke\smoke.c`），在不创建 GL 上下文的前提下
把核心跑四遍，验证数据解析与核心装载：

| 用例 | 期望 |
| :-- | :-- |
| 无内容 + `dist/system/pvz` 有数据 | 装载成功（BIOS 模式） |
| 内容 = 某个 `main.pak`（system 目录为空） | 装载成功（内容模式） |
| 无内容 + 空 system 目录 | 失败，并给出明确提示 |
| 内容不是 `main.pak` | 失败，并给出明确提示 |

---

## 7. 日志

核心只在**启动、出错、退出**时写日志，正常游玩期间是静默的（不再有周期性状态输出）。
游戏自身的日志（`Sexy::LogInfoLn` / `LogErrorLn`）也会镜像到前端日志，并统一补上行尾换行。

常见行：

| 日志行 | 含义 |
| :-- | :-- |
| `[pvz] Using BIOS game data from '<路径>' (properties/: yes)` | 走 BIOS 模式，已找到数据 |
| `[pvz] Using content-provided game data from '<路径>'` | 走内容模式，**未读取 BIOS** |
| `GL context: ... \| GLSL: ... \| vendor: ...` | 实际拿到的 GL / GLSL 版本（由游戏侧输出） |
| `[pvz] Game started (800x600).` | 游戏初始化完成，开始跑帧 |
| `[pvz] the game asked to shut down; telling the frontend.` | 游戏主动退出（通常是资源缺失） |
| `[pvz] This core needs hardware rendering ...` | 视频驱动不是 gl/glcore |

出错时的两条关键信息：`No game data found. ...` 与 `Unsupported content '...'`，
它们同时会以 OSD 提示显示在画面上。

---

## 8. 核心信息文件

`dist/cores/pvz_libretro.info` 是 RetroArch 用来显示**「核心信息」**的元数据文件，
必须与核心 DLL 放在同一目录（`cores/`）。它提供：

* 显示名称、版本、作者、制造商、支持的扩展名（`pak`）；
* `supports_no_game = "true"` —— 允许不带内容直接启动；
* **固件（BIOS）状态项**：`system/pvz/main.pak`、`system/pvz/properties/default.xml`、
  `system/pvz/properties/Layout.xml`，全部标为可选。RetroArch 会据此在
  「核心信息」里显示这几个文件是 ✓ 还是 ✗ —— 这也是启动日志里那行
  `Updating firmware status for ...` 的来源。

因为核心既能用 BIOS 也能用内容启动，这些固件项都声明为**可选**：
缺了它们核心仍可运行（内容模式下根本不读 BIOS）。

---

## 9. 核心选项（Core Options）

在 RetroArch 的 `快捷菜单 → 核心选项` 里，改动**立即生效**，不需要重启核心。

PvZ-Portable 自己的开关大多是**编译期**的（`PVZ_DEBUG`、`DO_FIX_BUGS`、`LOW_MEMORY`）
或**命令行参数**（`-cheat`、`-play` / `-record`），前端这两样都给不了，所以把玩家用得上
且能在运行期切换的部分做成了核心选项：

| 选项 | 取值 | 默认 | 说明 |
| :-- | :-- | :-- | :-- |
| `Gamepad Cursor Speed`<br>（`pvz_cursor_speed`） | `2` / `4` / `6` / `8` / `12` / `16` | `6` | 左摇杆 / 十字键移动虚拟光标的速度（像素 / 帧）。手柄玩家只有这一个指针，默认速度横穿 800×600 屏幕要 2 秒以上，点种子、点铲子都很累，可以调快。 |
| `Gamepad Deadzone`<br>（`pvz_gamepad_deadzone`） | `off` / `5%` … `60%` | `25%` | 摇杆死区。摇杆回中后光标自己漂移就调大；轻推不动就调小。 |
| `Core-Drawn Cursor`<br>（`pvz_draw_cursor`） | `auto` / `always` / `never` | `auto` | `auto`：只在手柄驱动光标时由核心画箭头（鼠标用户交给前端的光标）。若你的前端**不画**鼠标指针，选 `always` 就不会出现"完全没有指针"。 |
| `Cheat Keys`<br>（`pvz_cheat_keys`） | `disabled` / `enabled` | `disabled` | 解锁游戏内置的作弊键与调试显示 —— 等价于上游用 `PVZ_DEBUG` 构建并传 `-cheat`（上游的 `-cheat` 本身被 `#ifdef PVZ_DEBUG` 关掉，Release 版拿不到）。开启后在对局中可用：<br>`6` 20 倍速、`7` 慢动作、`8` 简单种植、`z` 切换调试信息叠加层。<br>不按这些键时不会改变任何游戏行为。 |

> 需要键盘才能按作弊键；注意 RetroArch 默认会吃掉绑定到手柄/热键的按键（见 3.1 节），
> 而 `6` / `7` / `8` / `z` 默认没有绑定，可以直接用。

没有做成核心选项的（原因）：

* `DO_FIX_BUGS`（11 项针对官方 1.2.0.1073 的社区 bug 修复）：上游是编译期宏，
  改成运行期开关需要把 `Zombie.cpp` 里 17 处 `#ifdef / #else / #endif` 重写成
  运行期分支（约 260 行，且多数位于僵尸 AI 逻辑中间），侵入性大、易与上游冲突，
  因此保留为构建选项（`-DDO_FIX_BUGS=ON`）。
* `LOW_MEMORY`：涉及 pak 内存映射与声音缓存**数据结构**的差异，不是运行期可切的。
* `CONSOLE` / `BUILD_STATIC`：纯构建选项，与游戏运行无关。
* `-play` / `-record` / `-playnum` / `-recnum`（演示录制与回放）：面向开发者做回归
  测试，文件落在进程工作目录，对前端玩家没有意义。
* `-screensaver` / `-crash` / `-version` / `-license`：桌面端调试用途，不适用。
* `-resdir` / `-savedir`：已由核心的数据加载规则（第 2 节）与前端存档目录取代。

游戏内 `选项` 已有的音乐音量、音效音量、3D 加速三项不重复提供；`全屏` 在 libretro 里
由前端的显示设置负责。

---

## 10. 已知限制

* **需要 GL 视频驱动**：RetroArch 的视频驱动要设成 `gl` 或 `glcore`，两者都支持：

  * `gl`（桌面 GL 兼容档）→ 用 GLSL 1.20 着色器；
  * `glcore`（GL 3.3 core）→ 着色器自动改用 GLSL 1.50 core 重编一次。

  另外 `RETRO_HW_CONTEXT_OPENGLES2` 只有在 RetroArch 编译时带了 `HAVE_OPENGLES`
  才会被接受（Windows 官方版一般没有），所以核心会**自动回退**请求桌面 GL 2.1 ——
  这是预期路径，日志里看到 “Frontend accepted a desktop OpenGL 2.1 context” 属正常。
  若视频驱动是 `vulkan` / `d3d11` 等，核心会给出提示并拒绝启动。
  核心同时把实际拿到的 `GL_VERSION` / `GLSL` / 厂商字符串打进日志，便于排查。
* **不支持倒带 / 前瞻**：`retro_serialize` 实现的是「关卡快照」（见 3.2），一份数百 KB，
  逐帧保存对前端不划算；核心也没有为此做优化。
* **存档位只在关卡内有效**：在主菜单/图鉴/商店等界面存档会被拒绝，读档则只在关卡中或
  主菜单里有效。
* **快速前进可用，但不支持倒带**：按住前端的快进键（RetroArch 默认 `L`）时，
  核心会把游戏时钟从「真实时间」切换为「每帧一帧游戏时间」——
  因为音频也是每帧产出一帧（44100/60 = 735 采样），这样游戏与 BGM 会保持同步，
  一起按前端帧率加速。快进时音频会随之变快变调，如需静音可在 RetroArch 里打开
  `设置 → 音频 → 快进时静音`。
* **音频在装载阶段静音**：装载线程结束前不混音。
* 未用 `--openmpt` 构建时，`sounds/mainmusic.mo3` 无法加载（BGM 静音，音效正常）。
  用 4.3 的步骤重建即可获得完整 BGM 与动态分轨。
* 音频驱动请用 `wasapi`：RetroArch 日志里出现
  “音频驱动启动失败，将在无音频模式下继续启动” 是前端音频后端的配置问题，与本核心无关。
