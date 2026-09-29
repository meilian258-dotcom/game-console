# SFC20 下载发送权限补充交接

2026-09-13，`/root/fix_sfc_av` 经 root 授权，补交叉审查发现的发送窗口；原 SFC20 handoff 及 v1/v2/v3 报告不覆盖。

## 唯一生产文件

`piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/server/SfcServerManager.java`

SHA-256：`C9B79ED4A42F85E012EE41B84DA4121C716DD2CE9C5C5D130537204BC22630C5`。

在原白名单上新增 nested class `cn/piq/sfcarcade/server/SfcServerManager$DownloadSendGuard`，无其他新生产源。外层新增发送事务与原连接失败处理。未改任何网络字段／版本、ROM／存档格式、客户端、核心或资源。

## 行为

- 每个 DownloadStart、DownloadChunk 发送前分别检查原 transfer 仍是 map 当前对象、bytes 尚存在、实际玩家／连接／session／BE／ROM／距离／原版 mayInteract；再发当前 RightClickBlock 保护事件；回调后再次查全部事实。没有沿用 20 tick 的权限缓存。
- 等待 worker IO 且无包待发时仅做廉价事实／期限检查，不额外派发权限事件。
- 同步回调取消、换会话、换传输或断线后不发送旧包。分片循环与完成操作先确认原 transfer 仍当前，避免回调将 bytes 清空后空指针或误清新事务。使用 snapshot 遍历，没有新增活 map iterator。
- 发送事务不可重入，异常会释放 guard；发送／回调 RuntimeException 或 LinkageError 走仅原事务清理。
- 拒绝或超时会关闭仍然原样的 session，并仅向它的原实际 Connection 提示失败；回调已替换 session 时不关闭或提示新局。
- 合法首次下载顺序仍为服务端建立 session/membership → sendActive → 客户端 ensureLocal/requestDownload，不要求核心已经开始，也不阻断首次缺 ROM 的加入。

## QA

新增 `tools/qa/SfcDownloadSendProbe.java`，接入 `tools/check_sfc_audit_fixes.py`。新增 `download_sending` 报告字段。实际执行生产 nested guard，验证无待发／权限撤回／回调替换六类身份／取消／重入／异常与连续非 20 倍数 tick；ASM 检查真实 Manager 编译路径中两种包都经过 guard、没有旁路、错误清理检查原 Connection。

最新源报告：`design/audit-fixes20-source-v4.json`，SHA-256 `168B41A81595871B5C54E03C0C0BEABF8B02A779C64DC72C9E0B7D29B4808638`。

- 58 项新增真实 guard／编译接线断言通过。
- 原 10,016 项下载准入与真实单文件 IO 断言通过。
- 原 266 项真实 WASM 修复积压断言通过，目标 720／head 920 时实际 916 才 ACK。
- 源预检真实 API 编译成功。首次仅新 QA 的 `var` 多变量声明语法错误，修正 QA 后通过；未修改生产来规避测试。v3 成功后额外加入发送异常隔离和 3 个断言，v4 为最新冻结证据。

最终包由 root 统一构建；仍用现工具，只移除 `--production-source`，它只编 QA 并核真实生产来源：

```text
python tools/check_sfc_audit_fixes.py --fc <final FC> --sfc <final merged SFC> --report <new report>
```

本组件现在生产／工具冻结，可以统一构建。未运行 Gradle、Minecraft、真实权限插件、网络服务器或用户 ROM；纯回调与 ASM 不冒充实际世界事件验收。维护手册由 root 汇总。
