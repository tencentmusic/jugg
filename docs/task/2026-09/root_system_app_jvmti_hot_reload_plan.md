# run-as 不兼容应用 JVMTI Hot Reload 修复方案

> 状态：已落地 / SystemAppDemo privapp 脚本级 L3 已通过 / 插件端到端待复测
> 关联报告：`d4fb4e20`
> 目标场景：Android Studio Apply Changes 依赖的 `run-as`、普通应用 UID 或应用可读 SELinux context 假设不成立，但设备能够通过普通 shell、root adbd 或 `su` 操作目标应用 data 目录

## 1. 结论

本次修复不再根据 `FLAG_SYSTEM`、`PRIVATE_FLAG_PRIVILEGED`、Manifest `sharedUserId` 或具体 `run-as` 错误文本决定部署通道，而是直接判断 Android Studio Deployer 所需能力是否成立：

```text
run-as 能完整执行
AND run-as 返回的原始 UID 位于 Android Studio Deployer 接受的 10000..19999 范围
AND run-as 新建探针的 SELinux context 与既有 code_cache context 一致
  -> 保留 Android Studio Apply Changes

其他可确认的 run-as 不兼容结果
  -> Jugg Direct Deploy
  -> 普通 shell 能完成 app data 操作时直接使用
  -> 否则尝试一次 adb root，等待重连后重新验证
  -> 仍不可用时尝试 su
  -> Direct Overlay -> Hot Reload -> 可降级失败时重启
```

这套规则同时覆盖：

- `android:sharedUserId="android.uid.system"` 对应的 appId 1000 应用。
- `/system/priv-app` 上因 `privapp_data_file` 等 SELinux 策略导致 `run-as` 失败的应用。
- 厂商 ROM 上其他无法使用 `run-as`、但存在可用 Direct Deploy 权限的应用。
- `adb shell` 默认是 root、需要执行 `adb root`、只支持 `su`，或普通 shell 已具备目标目录完整操作能力的设备。

现有 Direct Overlay、Jugg startup agent、dynamic attach 和重启降级语义保持不变。

## 2. 已确认的失败原因

### 2.1 当前识别逻辑会把未知错误误判为 RUN_AS

当前 `AppSandboxExecutor.resolveMode()` 执行：

```text
run-as <package> true 2>&1
```

只有输出命中 `package not an application` 才尝试 `ROOT_DIRECT`，其他所有输出都被当成 `RUN_AS`。因此以下真实错误会被误判为成功：

```text
run-as: couldn't stat /data/user/0/<package>: Permission denied
```

`RootSystemAppDeployTransport.canTry()` 又要求 `mode == ROOT_DIRECT`，最终未接管部署，继续进入 Android Studio Installer 并以 `errorId: 34` 失败。

执行 `adb root` 也不能修正识别：root adbd 下 `run-as` 可能变成成功，当前模式仍会得到 `RUN_AS`，Direct Deploy 依然不会被选中。

### 2.2 Apply Changes 存在两类已确认的不兼容

Android Studio Deployer 会通过 `run-as <package> id -u` 获取 UID，并通过 `run-as` 把 agent 和 install-server 复制到应用 `code_cache`。

同时，当前 Deployer 的 Base Swap 和 Live Literal Update 只接受原始进程 UID 位于 `10000..19999`。因此：

- system UID 应用即使某个 ROM 允许 `run-as`，UID 1000 仍会被 Deployer 的普通应用 UID 范围过滤。
- privileged app 可以拥有 10000+ UID，但 `run-as` 仍可能因 `privapp_data_file` 等 SELinux 策略无法进入 data 目录。

保留现场还确认了另一种边界：`com.jugg.demo.privapp` 的 `run-as` 返回 UID 10148 并能写入 `code_cache`，但 `run-as` 创建的目录为 `app_data_file:s0:c148,c256,c512,c768`，应用进程为 `platform_app:s0:c512,c768`。应用重启后对 `.overlay` 和 `startup_agents` 均出现 AVC denied。因此仅验证 UID 和写入成功仍会误判，必须同时验证新建文件与既有 `code_cache` 的 SELinux context 一致。

Direct 模式首次修正后又暴露出两个独立边界。第一，`restorecon` 成功时会输出 `SELinux: Loaded file context from:`，原实现把这些辅助输出拼在业务 `success` 后，导致 startup agent 准备被误判为失败。第二，递归 `restorecon` 会把 JVMTI `.so` 标记为 `app_data_file:s0`；即使改成与目录相同的 `app_data_file:s0:c512,c768`，`platform_app` 仍会因 `{ execute }` 被拒。设备实测将普通文件设为既有 `code_cache` context、将 `.so` 设为 `apk_data_file:s0` 后，overlay Dex 可读，startup agent 在冷启动后的 `/proc/<pid>/maps` 中成功加载。

`SystemAppDemo` 已确认：

| 应用 | appId | data 目录 SELinux 类型 | 普通 shell 下 run-as |
|---|---:|---|---|
| `com.jugg.demo.systemapp` | 10207 | `app_data_file` | 成功 |
| `com.jugg.demo.privapp` | 10148 | `app_data_file` | 成功，但新建文件 MCS categories 与应用进程不一致 |

这说明应用类型只能解释常见原因，不能直接代表 Apply Changes 的实际可用性。

## 3. 范围

### 3.1 本次修复支持

- Android 8.0 / API 26 及以上。
- 目标为默认主进程。
- payload 只包含 class Dex 变更。
- Apply Changes 能力探测明确不兼容后，进入 Jugg Direct Deploy。
- Direct Deploy 依次支持普通 shell、root adbd 和非交互 `su`。
- 方法体变更使用 Direct Overlay + 在线 redefine。
- 新类或结构变化等现有 Hot Fix 类变更使用 Direct Overlay + 重启应用。
- 同一次部署只解析一次 sandbox 模式，并在全部 Direct Deploy 组件中复用。

### 3.2 本次修复不支持

- 资源、assets、Manifest、native library 或 APK 更新进入 Direct Deploy。
- 多进程批量 attach。
- 主动加载尚未加载的类。
- 修改或 fork Android Studio installer/install-server。
- 通过应用类型推测 root 能力。
- 交互式 `su` 授权流程。

当 run-as 不兼容而 payload 超出 Direct Deploy 范围时，应提前报告当前变化不受支持，不再进入必然失败的 Android Studio Apply Changes。

## 4. Apply Changes 能力探测

### 4.1 探测目标

能力探测只回答一个问题：当前包是否满足现有 Android Studio Deployer 对 `run-as`、UID 和应用可读文件 label 的前置假设。

不再使用以下信息参与路由：

- `FLAG_SYSTEM`
- `PRIVATE_FLAG_PRIVILEGED`
- APK 安装路径
- Manifest `android:sharedUserId`
- `run-as` 错误文本列表

这些信息可以记录为排查日志，但不影响结果。

### 4.2 成功标记

通过一次最小、可回滚的命令验证 `run-as`、UID、`code_cache` 写入能力和新建文件 label：

```shell
run-as <package> sh -c '
  uid=$(id -u) || exit 1
  probe=code_cache/.jugg_run_as_probe_$$
  touch "$probe" || exit 2
  code_cache_context=$(ls -Zd code_cache) || exit 3
  code_cache_context=${code_cache_context%% *}
  probe_context=$(ls -Z "$probe") || exit 4
  probe_context=${probe_context%% *}
  rm -f "$probe" || exit 5
  printf "__JUGG_RUN_AS_OK__:%s\n" "$uid"
  printf "__JUGG_RUN_AS_CONTEXT__:%s|%s\n" "$code_cache_context" "$probe_context"
'
```

判断规则：

```text
ADB 调用本身异常或设备 offline
  -> 传播 transport 异常或复用现有 transient offline retry
  -> 不得误判为 run-as 不兼容

命令正常返回但没有唯一成功标记
  -> RUN_AS_INCOMPATIBLE

存在成功标记，但原始 UID 不在 10000..19999
  -> RUN_AS_INCOMPATIBLE

存在成功标记和 context 标记，但探针与 code_cache 的 SELinux context 不一致
  -> RUN_AS_INCOMPATIBLE

存在成功标记，原始 UID 在 10000..19999，且两个 SELinux context 一致
  -> RUN_AS_COMPATIBLE
```

这里按 Android Studio Deployer 当前实现检查原始 UID，不把多用户 UID 转换为 appId。Deployer 自身直接比较 `/proc/<pid>` 的 `st_uid` 与 `10000..19999`，能力探测必须与其保持一致。

成功标记必须严格整行匹配，不能把空输出或未知 stderr 当成成功。探测文件无论成功或失败都进行 best-effort 清理。

## 5. Direct Deploy sandbox 获取

只有能力探测得到 `RUN_AS_INCOMPATIBLE` 后才解析 Direct Deploy sandbox。解析结果固定为本次部署会话的一部分：

```text
DIRECT_SHELL
ROOT_DIRECT
SU_ROOT
UNAVAILABLE
```

### 5.1 普通 shell 优先

先在 PackageManager 返回的真实 `dataDir` 下执行可回滚的完整能力探测。只验证 `cd` 或 `test -w` 不足以代表 Direct Deploy 可用，探测必须覆盖：

- 进入 `dataDir/code_cache`。
- 创建并删除临时文件。
- 将临时文件 owner/group 修正为应用目录 owner/group，或确认创建结果已经一致。
- 优先通过 `chcon` 让普通文件继承既有 `code_cache` 的完整 SELinux context，并把 `.so` 修正为 `apk_data_file:s0`；仅在缺少 `chcon` 时使用 `restorecon` 降级。
- 验证最终 owner/group 和必要的 SELinux 处理结果。
- 输出唯一成功标记。

普通 shell 能满足全部条件时使用 `DIRECT_SHELL`，不执行 `adb root`。这覆盖厂商为 shell 提供额外 DAC、capability 或 SELinux 权限的设备。

### 5.2 root adbd

普通 shell 探测失败后：

1. 执行 `id -u`，若当前 shell 已是 UID 0，记录失败为 root shell 仍无目标目录能力，不重复执行 `adb root`。
2. 当前 shell 非 root 时，通过宿主机 adb CLI 执行一次：

   ```text
   adb -s <serial> root
   ```

3. `adb root` 会重启 adbd。等待同一 serial 恢复为 `device`，再重新执行完整目录能力探测。
4. 只有重新探测成功才进入 `ROOT_DIRECT`。不能根据 `adb root` 输出文本直接判定成功。
5. 每次部署最多请求一次 `adb root`；重试必须以 adbd 状态变化为前提。

`adb root` 成功后不需要每条命令重复执行。后续命令直接通过 root shell 运行，直到设备重启、adbd 重启、执行 `adb unroot` 或连接失效。

现有 3 秒 transient offline 等待不一定足以覆盖 adbd 重启；`adb root` 使用独立、有上限的重连等待，并在恢复后重新验证能力。

### 5.3 su

`adb root` 不支持或重连后能力仍不可用时，以短超时探测非交互 `su`：

```text
su 0 sh -c '<完整能力探测脚本>'
su -c '<完整能力探测脚本>'
```

只接受完整成功标记。超时、交互授权、拒绝或输出不完整均视为不可用。

`su` 不改变 adbd 身份，每次独立的 adb shell 都需要通过 `su` 包装。相关文件操作应尽量合并成单个脚本，减少进程切换并保持阶段原子性。

### 5.4 权限不可用

三种 Direct 模式都不可用时提前失败，错误至少包含：

- package name
- run-as 能力探测结果
- 当前 shell UID
- 普通 shell 目录探测结果
- `adb root` 是否请求、是否恢复、恢复后的探测结果
- `su` 是否不可用

不得返回 `null` 继续进入 Android Studio Apply Changes，也不得伪造部署成功。

## 6. App sandbox 执行模型

`AppSandboxExecutor` 不再通过错误文本推断应用类型。它持有一次部署已经解析完成的模式和真实 `dataDir`：

- `DIRECT_SHELL`：普通 `sh -c`，作用域固定在真实 `dataDir`。
- `ROOT_DIRECT`：root adbd 的普通 `sh -c`，作用域固定在真实 `dataDir`。
- `SU_ROOT`：每个需要权限的完整脚本通过已验证的 `su` 形式执行。
- `UNAVAILABLE`：明确失败。

`RUN_AS_COMPATIBLE` 不需要创建 Direct Deploy sandbox，继续走 Android Studio Apply Changes。

同一次部署必须复用一个已解析 executor。`DirectOverlayWriter`、`DirectOverlayStateChecker`、startup agent pusher、`JuggJvmtiAgentManager`、`RootHotReloadWriter` 和清理逻辑不得分别重新创建 executor。否则 `adb root` 前后的 `run-as` 结果变化会导致同一轮部署中途切换模式。

所有 app-relative 路径继续以 PackageManager 返回的 `dataDir` 为根，禁止硬编码 `/data/data/<package>` 或 `/data/user/0/<package>`。

## 7. 部署路由

`JuggDeployer.optimisticSwap()` 在 Android Studio Deployer 之前完成能力探测：

```text
API >= 26
AND payload 只包含 class Dex 变更
AND Apply Changes 能力 == RUN_AS_INCOMPATIBLE
  -> DirectAppSandboxDeployTransport

Apply Changes 能力 == RUN_AS_COMPATIBLE
  -> 现有 Direct Overlay 用户通道或 Android Studio Deployer

Apply Changes 能力 == RUN_AS_INCOMPATIBLE
AND payload 超出 Direct Deploy 范围
  -> 提前报告不支持
```

现有 `RootSystemAppDeployTransport` 的职责已经不再限定 root 或 system app，落地时改名为 `DirectAppSandboxDeployTransport`。`RootHotReloadWriter` 同理改为不携带 root 身份假设的名称，例如 `DirectHotReloadWriter`。

Direct transport 的固定顺序：

1. 解析并固定 Direct Deploy sandbox；不可用则失败。
2. 准备并安装现有 Jugg startup agent。
3. 检查设备 `.overlay/id` 与本地缓存的一致性。
4. 使用现有 `DirectOverlayWriteRequestBuilder` 和 `DirectOverlayWriter` 写入 overlay。
5. payload 只有 `hotReloadModifiedClasses` 且主进程在线时尝试动态 attach。
6. 在线成功时返回成功且不重建 Activity。
7. 在线失败、类未加载、类不可修改或 redefine 失败时标记需要重启；startup agent 加载步骤 4 的 overlay。
8. 新类、结构变化等 Hot Fix payload 不尝试 redefine，直接标记需要重启。

成功写入 overlay 后，无论在线生效还是重启降级，都提交同一个 overlay id 和部署历史。

## 8. Hot Reload 与持久化语义

现有 Jugg agent 继续同时承担：

- startup 模式：agent options 是应用 data 目录，进程启动时加载 `.overlay`。
- dynamic attach 模式：agent options 是 `jugg_hot_reload:<requestDir>`，读取本次请求并调用 JVMTI `RedefineClasses()`。

固定执行顺序仍是先持久化、再在线生效：

```text
增量 Dex
  -> Direct Overlay 写入 code_cache/.overlay
  -> 动态 attach 现有 Jugg JVMTI agent
  -> 在线成功：当前进程立即生效，overlay 保证下次启动继续生效
  -> 可降级失败：重启主应用，由 startup agent 加载同一 overlay
```

不重新编译、不生成第二套 payload。attach 命令成功不代表 redefine 成功，仍以 agent 原子写入的 `result.txt` 为准。

## 9. 失败边界

| 失败阶段 | 行为 |
|---|---|
| ADB transport/offline | 传播或复用现有 transient retry，不误判为 run-as 不兼容 |
| run-as 无成功标记、UID 越界或 SELinux context 不一致 | 进入 Direct Deploy |
| Direct sandbox 全部模式不可用 | 提前失败，不进入 Apply Changes |
| run-as 不兼容且 payload 不受支持 | 提前报告 Direct Deploy 当前只支持 class Dex |
| startup agent bundle/安装失败 | 部署失败，不写 overlay |
| overlay 写入前失败 | 部署失败，可在改变失败条件后最多重试一次 |
| overlay 写入后 dirty | 保留真实异常，不重新执行整段写入 |
| Hot Reload 请求 staging 失败 | overlay 已有效时重启降级，否则失败 |
| app 未运行、类未加载、不可修改、redefine 失败 | 重启主应用，由 startup agent 加载 overlay |
| attach 超时或结果格式错误 | 不重试 attach；overlay 有效时重启降级并记录原因 |
| 应用重启失败 | 保留最终异常，不伪造部署完成 |

只有已知且可恢复的在线替换失败才降级。权限、agent 准备和 overlay 持久化失败不能通过重启掩盖。

## 10. 预计改动

### 10.1 生产代码

- 将 `AppSandboxExecutor` 的错误文本识别改为成功标记和固定模式。
- 增加 Apply Changes run-as/UID/SELinux context 能力探测结果，区分兼容、不兼容和 transport 失败。
- 在 `IDeviceAdb` 增加一次性请求 root adbd 的专用能力，由 `IdeaDeviceAdb` 通过 adb CLI 实现。
- 增加普通 shell 完整目录能力探测和非交互 `su` 模式。
- 同一次部署复用一个 sandbox executor，并传递给 Direct Overlay、agent、Hot Reload 和清理组件。
- 将 `RootSystemAppDeployTransport` 重命名并调整为能力驱动的 Direct transport。
- 将只表达 root 假设的 Hot Reload 类名调整为 Direct Deploy 语义。
- 保留现有 JVMTI request handler、batch redefine、overlay id-last 和 dirty-state 实现。

### 10.2 文档

- 更新 `docs/ai_knowledge/03_deploy_system_app.md`，说明能力驱动的兼容边界。
- 更新 `docs/ai_knowledge/03_deploy_core.md` 和代码地图中的 transport/sandbox 职责。
- 检查 `docs/wiki` 是否已有 Apply Changes 或系统应用部署页面；存在时同步中英文内容。

## 11. 验证

自动化测试保护的是部署路由和权限模式等稳定可观察行为，具有独立回归价值。

| 层级 | Owner / 证据 | 预期 |
|---|---|---|
| L1 | Apply Changes capability probe | 成功标记、UID 10000..19999 且探针 context 与 code_cache 一致时兼容；无标记、UID 1000、UID 越界或 context 不一致时不兼容；ADB 异常不被吞成不兼容 |
| L1 | `AppSandboxExecutorTest` | 普通 shell 完整能力成功时不请求 root；失败后最多请求一次 adb root；重连后重新探测；su 命令逐脚本包装；修复辅助输出不污染业务结果；全部失败时返回真实原因 |
| L1 | Direct Overlay 既有测试 | executor 复用后 overlay 的 id-last、dirty 失败和状态检查语义不变 |
| L1 | Hot Reload request writer 测试 | 请求格式、路径、结果解析、超时和失败分类正确 |
| L2 | deploy flow 等价回归 | run-as 兼容时保持 AS 路径；不兼容时不调用 AS swap；Direct 权限失败不回落 AS；在线成功不重启；可降级失败请求应用重启 |
| 编译 | 定向测试、`:idea:compileKotlin`、JVMTI agent bundle 构建 | Kotlin 与 native 构建通过 |
| L3 | `SystemAppDemo/systemapp` | 普通 shell 下 run-as 探测成功，保持 Apply Changes 路径 |
| L3 | `SystemAppDemo/privapp` | 已验证 run-as context 不一致时进入 root Direct；普通 Dex 继承 `code_cache` context，agent `.so` 使用 `apk_data_file:s0`，冷启动后 agent 出现在进程 maps；插件端到端部署待复测 |
| L3 | AOSP platform-signed system UID app | UID 1000 不进入 AS deployer；Direct Overlay 在线生效，或重启后由 startup agent 生效 |

如果没有可取得 UID 1000 的 AOSP 镜像，必须明确保留该项为待设备验证，不能用普通 system/privileged app 结果替代。

## 12. 验收标准

- 路由不依赖 `run-as` 错误文本、`FLAG_SYSTEM`、`PRIVATE_FLAG_PRIVILEGED` 或 Manifest `sharedUserId`。
- `run-as` 只有出现唯一成功标记、原始 UID 位于 `10000..19999`，且探针与既有 `code_cache` 的 SELinux context 一致，才视为 Apply Changes 兼容。
- ADB transport 失败不会被误判成 Direct Deploy 条件。
- 普通 shell 已具备完整 app data 操作能力时不执行 `adb root`。
- `adb root` 每次部署最多执行一次，重连后以完整能力探测结果为准，不按输出文本判断。
- `su` 模式对每个权限脚本进行包装，不重复请求 `adb root`。
- Direct sandbox 不可用或 payload 不受支持时提前失败，不进入必然失败的 Android Studio Deployer。
- 同一次部署固定并复用一个 sandbox executor，不因 adbd 状态变化中途切换模式。
- 方法体修改先持久化 overlay，再使用现有 Jugg agent 在线 redefine。
- 在线成功后不重建 Activity，重启应用后修改仍然有效。
- 在线失败且 overlay 有效时自动重启，startup agent 加载同一 overlay。
- 普通 run-as 兼容应用保持现有 Apply Changes 行为。
- 日志能区分 run-as 能力、UID 范围、sandbox 模式、root 请求、overlay 结果、Hot Reload 结果和重启降级原因。
