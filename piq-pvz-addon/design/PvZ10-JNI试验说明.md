# PvZ10 + 电脑9：JNI 可选试验

2026-09-28，MC1.21.1 / NeoForge21.1.236 / Java21，配套已有FC76.17。候选，仅本地试验，未安装、部署、发布、重启。不是公开上架包或完整SDK，第三方许可边界见 [THIRD_PARTY](../THIRD_PARTY.md)。

## 安装和开启

1. 先保存退出并备份测试世界，建议用世界副本。双方更新 `game_console_pvz-0.1.0-prototype.10.jar`、`game_console_computer-0.1.0-prototype.9.jar`；旧版同组件移出mods保留，不能混装PvZ9和电脑9。单人只改客户端mods。共同方块/BE、服务端授权与串流消息需要双方安装，并非DLL要在服务器运行。
2. 主包FC76.17、街机1.5.2、SFC41/core9、GBA10和Flash完整运行器0.1.3均沿用，本次只有两个JAR，不需更新主包。
3. 电脑配置打开“选择程序”，选“PvZ”，浏览本机合法 `main.pak`；旁边properties文件夹沿用。文件和完整路径不会上传给服务器，不包括任何游戏素材。
4. 先结束正在运行的程序，再点“下次启动：独立进程（默认）”，确认JNI风险；按钮变为“JNI试验（独立试验档）”。点“使用此程序”，按原流程右键已配对的键鼠开始。
5. 回退：结束当前程序并等退出完成，切回“独立进程”，再次启动。原进度没有被迁入或覆盖。旧PvZ测试播放盒仍只用独立进程，不添加另一处开关。

这是**运行者本机偏好**，保存在 `config/piq-computer-client.properties` 的 `pvz.engine`，默认/未知值都为process；不是全服规则，不写管理终端。远端旁观/接管只沿用音画和输入，不启动另一份JNI。同一客户端最多一个JNI会话。

## 风险与保存

- JNI把核心装进Minecraft进程：原生访问异常、退出进程或驱动错误可能带崩整个客户端，Java异常捕获不能保证拦住。请先备份世界。没有把现有独立进程默认替换掉。
- 初始化45秒未完成会提示超时；退出最多等12秒，若核心不返回，保留占用和目录，不强杀线程/强卸DLL。此时保存Minecraft世界后正常重启客户端，不再尝试新JNI会话。若整个客户端无响应，可能仍有未保存数据。
- JNI单独保存到 `游戏实例/game-console/piq-pvz/jni-saves/<SHA256(玩家UUID+冒号+main.pak摘要)>/PvZ-Portable/`；摘要用大写64位十六进制。目录身份包含玩家和资源，不共享给其他人。备份整个 `jni-saves` 即可。文件锁防止两个运行器写同档。
- 原独立进程仍用 `game-console/piq-pvz/saves/<玩家UUID>/<资源摘要>/`，不自动导入/迁移原档。试验中新进度也不自动回灌旧档。真实关卡保存/中途继续需要另验，正常退出不代表每个游戏场景都可保存。
- 当前固定核心存在长缓存路径限制。JNI数据目录/保存根的UTF-8长度超过160字节会在加载前拒绝，提示缩短实例路径或回退。不要改全局工作目录或把核心错误吞掉冒充成功。
- 正常退出只清自己的游戏临时目录。JNI桥约620KiB，每个Java进程加载一次；Windows可能在退出时仍锁定DLL，少量桥临时文件可能留待客户端退出后清理。崩溃产生的临时游戏目录保留现场，不自动批量清理。
- 只支持Windows x64，需OpenGL兼容上下文。服务端不加载JNI。无独立RetroArch安装，无网络下载或服务器指定DLL执行。

## 性能和现有边界

核心仍是原固定DLL。JNI用独立渲染线程/WGL上下文，画面直接写入DirectByteBuffer，再转入已有三缓冲和电脑纹理；**不是GPU零拷贝**。音频44.1kHz双声道，有界队列；旁观继续使用电脑8的JPEG/PCM、96/192/384KiB/s限速，没有提高全服流量或改网络协议。

因此JNI只减少本机子进程通信，不能承诺Minecraft帧率提升、延迟下降或旁观不卡。状态里的“核心/s”“收图FPS”“单步含抓图ms”不是Minecraft FPS。距离/租约/关机/共享设置不改：电脑操作8格、串流32格既有规则仍在，不等同街机16格全服规则。

验证结果见同包 `VERIFICATION.md`。Minecraft GUI、实际多人、光影/其他GPU、弱网、长时游玩与关卡存档续玩仍待验，不标稳定。

## 开发

入口：`runtime/PvzEngine`、`PvzJniRuntime`、`PvzJniWorkspace`、`PvzJniInputs`、包内固定桥`PvzJniBridge`及`native/pvz_jni.cpp`。原PvzRuntime只改为实现同接口，算法/host/core不变；电脑PvzComputerBackend三参数构造保留独立进程默认。

JNI资源固定SHA256校验，不接受远程/任意DLL。原生桥不修改进程cwd、全局DLL搜索规则或MC渲染上下文；所有核心调用和关闭在同一个工作线程，有token/线程/DirectBuffer容量/输入数量检查。提供的第三方二进制本身仍有未审计风险。

`tools/build_jni.py` 使用现有固定LLVM-MinGW和Java21头文件，仅重建独立JNI桥；生成receipt后须人工核对并更新PvzJniBridge固定SHA，不能自动信任替换DLL。Java按项目Gradle check/jar。`tools/JniProbe.java`只能在一次性测试JVM执行，绝不能嵌到MC去做错误注入。源码补充包含桥/头文件/构建脚本，不含上游完整源码、游戏和存档；原核心对应源码/依赖分发清单公开前仍需核验。
