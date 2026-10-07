# 方块电玩电脑：Windows 实验运行环境 0.1.3

2026-09-26 电脑5专用串流修订。完整目录安装至 `实例/game-console/piq-flash-box/runtime-stream-0.1.3/`，不要覆盖旧0.1.2。旧播放盒MOD不能使用此版新增IPC；电脑5使用自己固定的30文件清单。Ruffle版本不变，系统依赖仍为既有.NET Desktop6/WebView2。

新增输出 `{"kind":"audio","rate":24000,"pcm":"base64"}`：每块1200个单声道16位小端样本（2400字节），来自**此WebView游戏的WebAudio图**，不读取麦克风或系统混音。原本机音频路由保留，电脑5旁路传给远端；运行者系统音量仍自行调节。采集失败发audio-error并在电脑状态提示。最多4块待发音频及1帧待发画面；暂停清音频，捕获范围不含其他应用。

新构建：`dotnet publish FlashBox.Helper.csproj -c Release -o publish-0.1.3 --no-restore`，随后 `python package_runtime.py --publish publish-0.1.3 --version 0.1.3`；电脑内清单须重新冻结。独立真实森林神殿启动取得非零PCM；不是所有SWF音频方式、MC真实联机或精确音画延迟验收。

以下是0.1.2历史说明，其中“仅本机/无网络”指helper本身；网络由电脑5承担，不是新Netplay核心。

## 历史：0.1.2

这是独立的本机实验 helper，不是 Flash 官方播放器，也不是已完成的省流联机实现。
Ruffle 固定为官方 nightly-2026-09-16；代码和运行库不包含任何商业游戏 SWF。

## 运行前提

- Windows x64；已有 Microsoft .NET Desktop Runtime 6.0 与 Edge WebView2 Runtime。
- 本次在 .NET 6.0.18 / WebView2 153.0.4234.32 上验证；不自动安装或更新系统组件。
- .NET 6 是本机既有实验依赖，不作为未来公开发行版的长期技术选型承诺。
- 完整保留 helper、DLL、web、vendor 目录和 runtime-manifest.json；不得只复制 EXE。
- 用户自行准备有权使用的本机 SWF；不上传、不请求服务器游戏库。

运行：`FlashBox.Helper.exe --swf <绝对路径.swf> --width 640 --height 480`

默认使用不激活的屏幕外窗口提供真实 WebView 合成表面；不是最小化窗口。
默认不显示播放器，也不抢前台；显式指定 `--preview` 才打开独立可见调试窗口。
游戏声音直接由本机 WebView 系统音频输出，未接 Minecraft 音量、距离或空间音效。
退出会释放按键、浏览器并清理独立随机临时用户目录；首版不保存 Flash SharedObject 进度。
已发现短启动立即退出时 WebView 可能暂锁其空目录，helper 会在stderr如实提示清理未完全结束；不会删除其他浏览器目录。

## JSON 行 IPC

标准输入每行最多 8192 字符，最多排队 64 条；超限拒绝并退出。标准输出仅 JSON 行。

输入示例：

```json
{"op":"keys","p1":2,"p2":4}
{"op":"mouse","x":320,"y":240,"down":true}
{"op":"mouse","x":320,"y":240,"down":false}
{"op":"pause"}
{"op":"resume"}
{"op":"close"}
```

两个 mask 独立，bits：左1、右2、上4、下8、动作16。默认 P1 对应箭头/空格，P2 对应 A/D/W/S/左Shift。
`keys` 是完整状态替换，不是一次按键；按住后必须发送 0 松开。暂停、关闭、stdin EOF 也会松键。
鼠标是640×480画布坐标。首版键鼠均只作用此 helper，不发送系统全局模拟输入。

输出 `kind`：

- `ready`：version、runtime、width、height、swfSha256、audio、preview、format（默认 `jpeg`）。
- `frame`：seq、width、height，以及 `jpeg`（JPEG base64）；用 `--format png` 启动时图片字段改为 `png`。每帧只有一种图片字段，必须与ready声明一致。JPEG会有轻微有损压缩；接收端按声明检查文件头、640×480尺寸和长度上限后解码。捕获调度目标30fps，但并不保证实际达到。每次仅一个捕获，标准输出只保留一个最新待发帧；慢速接收可出现seq跳号，这是主动丢弃旧帧，不是协议损坏。暂停仍持续发帧。
- `error`：message、fatal；退出码非0表示失败。诊断日志走stderr。

`--bindings <JSON绝对路径>` 可覆盖两组五个键，不需要改游戏特定代码。
格式：`{"p1":[{"key":"ArrowLeft","code":"ArrowLeft","virtualKey":37},...共5项],"p2":[...共5项]}`。
每组顺序仍是左、右、上、下、动作；virtualKey限1..255，文件不超过8KiB。

## 安全及能力边界

- 只读取指定SWF（压缩文件≤64MiB、声明展开≤128MiB）和固定发布包内的JS/WASM。
- Web资源请求只服务隔离虚拟域下明确列出的资源；其他请求返回403；阻止外部导航、新窗口、下载、主机对象与权限请求。
- Ruffle 禁止脚本外部接口及外部导航；CSP限制页面资源与连接来源。
- 这是应用层隔离，不是对任意恶意SWF/浏览器漏洞的安全认证；不要加载来源不明文件。
- 首版是本机真实运行/菜单/输入/画面管道验证，不含远程双人、网络锁步、快照、存档与跨平台支持。
- 森林冰火人仅用于独立smoke脚本；生产helper不硬编码该游戏。

## 构建与证据

`dotnet publish FlashBox.Helper.csproj -c Release --no-self-contained -o publish-0.1.2`

随后 `python package_runtime.py` 为 `runtime/publish-0.1.2` 生成所有发布文件的大小与SHA-256清单。旧 `bin/Release/net6.0-windows/win-x64/publish`（0.1.0）及 `publish-0.1.1` 保留，不覆盖。Java侧应固定并核对清单本身的已知SHA，不能信任任意替换后的清单。

0.1.1将有序键鼠输入泵与截图调度分离，避免输入等待下一次截图tick。独立同机同关卡15秒稳态实测：0.1.0为6.94帧/秒，0.1.1为9.98帧/秒；P95帧间隔约161.7→111.8ms，未达到30帧。数据是本机PNG接收速度，不是游戏内部帧率，也不是MC最终显示帧率或联机带宽。实际性能随场景、设备和同时运行程序变化。

0.1.2采用WebView2原生JPEG捕获，保留PNG诊断回退。相同Forest第一关各15秒实测：PNG 10.075帧/秒、JPEG 21.167帧/秒；CapturePreview平均耗时81.73→26.91ms，平均图片438663→76917字节。尺寸均直接为640×480，没有为了提速降低分辨率。JPEG对照图确认角色、文字和地形正常，但不是无损画质。不保证Minecraft开启光影时也达到此独立helper帧率。

`--metrics` 仅供短时诊断：stderr输出 `PERF_CAPTURE` 和 `PERF_WRITE` JSON，按seq统计捕获（包括WebView内部编码）、归一化、base64/JSON入队及管道写出耗时；默认关闭，不影响stdout协议。WebView内部截图和内部图片编码不能由此API再细分，不能将CapturePreview耗时全部声称为编码耗时。

`performance_smoke.py` 的区域变化探针会受角色待机动画影响，不能作为可靠输入延迟结论；本次仅确认两组按键实际分别移动角色，不宣称输入延迟降低了多少毫秒。`protocol_performance_smoke.py` 检查长暂停心跳、慢读者丢旧帧、关闭不被输出反压阻塞及非法输入拒绝。
测试 SWF、截图和运行日志不得进入发布包。
许可证：vendor/LICENSE_MIT、vendor/LICENSE_APACHE；WebView2 为 LICENSE-WebView2.txt。
