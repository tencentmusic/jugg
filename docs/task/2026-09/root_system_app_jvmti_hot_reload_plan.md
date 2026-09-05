# Root 系统应用 JVMTI Hot Reload 方案

> 状态：方案 / 待确认 / 未授权实现
> 关联报告：`d4fb4e20`
> 目标场景：应用为 debuggable 系统应用，`run-as <package>` 返回 `package not an application`，但 `adb shell` 为 root

## 1. 结论

对满足条件的系统应用，Jugg 不再调用 Android Studio Deployer 的 Apply Changes 执行链路，而是复用现有增量编译和类结构分类结果，自行完成：

```text
hotReloadModifiedClasses
  -> root 写请求和 Jugg agent 到 app code_cache
  -> am attach-agent
  -> Agent_OnAttach
  -> GetLoadedClasses + GetClassSignature
  -> JVMTI RedefineClasses
  -> 结果文件
  -> Jugg 确认成功后提交部署历史
```

首版只覆盖 **纯代码 HOT_RELOAD、主进程、已加载且结构未变化的类**。普通应用和 `run-as` 可用设备继续走原 Apply Changes，避免扩大行为影响面。

## 2. 当前行为与缺口

### 2.1 已确认事实

- 报告 `d4fb4e20` 中目标应用为 UID 1000 系统应用，Android Studio install-server 复制阶段依赖 `run-as`，最终以 errorId 34 失败。
- 用户现场确认 `adb shell` 直接为 root，因此具备绕过 `run-as` 写入应用数据目录的前提。
- `JuggDeployData.hotReloadModifiedClasses` 已保存在线可替换类的 Dex 和 `ClassNode.className`；agent 不需要自行推断哪些类发生变化。
- `DeployDataGenerator` 已把 multi-class Dex 和 library Dex 归入 Hot Fix，因此首版 HOT_RELOAD 请求天然是一项 Dex 对应一个目标类。
- `JuggDeployTask.perform()` 当前最终调用 `JuggDeployer.fullSwap()` / `codeSwap()`，仍进入 Android Studio Deployer。
- 当前 Jugg native agent 已声明 `can_redefine_classes`，但 shell attach 分支为空，只处理 startup agent 初始化和 framework retransformation。
- 当前 `JuggJvmtiAgentManager` 的 app sandbox setup、agent 查询和位数判断全部依赖 `run-as`，系统应用场景无法直接复用。
- 修改 native agent 后必须递增根工程 `agentVersion`，否则设备可能继续复用旧 bundle。

### 2.2 尚待真机确认

- 目标 ROM 是否允许 root shell 对该 UID 1000 debuggable 应用执行 `am attach-agent`。
- 同一 Jugg agent 已作为 startup agent 加载后，再次动态 attach 相同 ELF 的 OEM 行为是否稳定。
- root 写入 `code_cache` 后所需的 owner、mode 和 SELinux label；预计需要 `chown`、`chmod`，并在设备提供 `restorecon` 时修复 label。
- ART 对目标应用具体构建和混淆产物执行 `RedefineClasses` 的实际错误码。

## 3. 范围

### 3.1 首版支持

- Android 8.0 / API 26 及以上。
- `adb shell id -u` 为 `0`。
- `run-as <package> true` 命中已知不可用错误，如 `package not an application`。
- 应用进程已运行，首版仅处理默认主进程。
- 部署数据只有 `hotReloadModifiedClasses`：
  - 无新类；
  - 无 Hot Fix 类；
  - 无资源、assets、Manifest、native library 或 APK 更新；
  - 每个目标类已经过现有结构兼容判定。
- 所有目标类均已加载且 `IsModifiableClass` 返回 true。

### 3.2 非目标

- 不修改或 fork Android Studio installer/install-server。
- 不替换普通应用的 Apply Changes。
- 不处理新增类、字段、方法签名、继承关系等结构变化。
- 不在首版支持资源混合部署、Activity 自动重建或多进程批量 attach。
- 不通过主动加载类来规避“类未加载”；避免触发静态初始化和 ClassLoader 副作用。
- 不把 attach 命令返回当作部署成功；必须收到 agent 结果文件。

## 4. 方案比较

| 方案 | 正确性与兼容性 | 实现/维护成本 | 结论 |
|---|---|---|---|
| A. Jugg 自有 root staging + JVMTI redefine | 不依赖 AS install-server；目标类由 Jugg 精确提供；仅绑定 Android JVMTI | 中；需要 host 协议和 native redefine | **推荐** |
| B. 修改 AS Deployer/install-server 使用 root 代替 `run-as` | 可继续复用 AS code swap/full swap | 高；强绑定各 AS 版本内部实现和 protobuf/native installer | 不采用 |
| C. root 写 overlay 后重启 App | 简单、稳定，但不是在线 Hot Reload | 低 | 作为后续 Hot Fix 兼容方案，不代替本方案 |

## 5. 推荐设计

### 5.1 命中条件与路由

在每个 `applicationId` 的 scoped deploy data 进入 `JuggDeployer.fullSwap()` / `codeSwap()` 前尝试自有 transport：

```text
API >= 26
AND adb shell uid == 0
AND run-as 对当前 package 命中已知不可用错误
AND app 主进程在线
AND payload 为纯 hotReloadModifiedClasses
    -> RootJvmtiHotReloadTransport
ELSE
    -> 原 Jugg / Apply Changes 路径
```

root 可用但 `run-as` 正常时不接管，确保普通 rooted 调试设备行为不变。探测失败或输出不明确时也不接管。

### 5.2 请求目录与协议

Host 先生成 zip 并 push 到 `/data/local/tmp/jugg/hot-reload/`，再由 root 一次性解压到：

```text
/data/user/0/<package>/code_cache/jugg_hot_reload/<requestId>/
  request.txt
  dex/0.dex
  dex/1.dex
  result.tmp       # agent 写入中
  result.txt       # 原子完成标记
```

`request.txt` 使用无需第三方 JSON 库的严格文本协议：

```text
JUGG_HOT_RELOAD_V1
0.dex\tLcom/example/MainActivity;
1.dex\tLcom/example/Presenter;
```

约束：

- descriptor 直接取 `ClassDeployItem.classNodes.single().className`。
- Dex 内容直接取 `ClassDeployItem.content`。
- 文件名由 Host 顺序生成，不接受目标应用提供的路径。
- descriptor、相对路径、请求数量、单文件大小和总大小均设上限。
- root 脚本在 rename 为最终请求目录前完成解压、owner/mode 修正和可用时的 `restorecon`。

### 5.3 Agent 准备与 attach

现有 agent bundle 仍 push 到 `/data/local/tmp/jugg/{AGENT_VERSION}`。系统应用命中 root 模式时：

1. 根据 app 位数选择 `jugg_jvmti_agent.so` 或 `_alt.so`。
2. root 将 so 复制到请求根目录下的版本化动态 attach 路径。
3. 修正 owner、目录权限和 SELinux label。
4. 执行：

```shell
am attach-agent <package> <absolute-agent-path>=jugg_hot_reload:<absolute-request-dir>
```

动态 attach 使用独立复制的 so，而不是直接复用 startup agent 路径，避免依赖 OEM 对同一路径重复 `dlopen` 的行为。是否确有必要保留独立副本由 Phase 0 真机结果决定。

### 5.4 Native redefine

`Agent_OnAttach` 按 options 分派：

```text
以 / 开头
  -> 保持现有 startup agent 行为

以 jugg_hot_reload: 开头
  -> HandleHotReloadRequest

其他
  -> 明确记录 unknown options，返回失败结果
```

`HandleHotReloadRequest`：

1. 校验请求路径必须位于当前应用 `code_cache/jugg_hot_reload/`。
2. `AddCapabilities(REQUIRED_CAPABILITIES)`。
3. 解析 request，读取并校验 Dex header/大小。
4. `GetLoadedClasses()`，再用 `GetClassSignature()` 建立 descriptor 到 `jclass` 的映射。
5. 对每个目标执行 `IsModifiableClass()`；任一类缺失或不可修改时不调用 redefine。
6. 构造完整的 `jvmtiClassDefinition[]`，一次调用 `RedefineClasses()`。
7. 将结果写入 `result.tmp`，`fsync` 后 rename 为 `result.txt`。
8. 释放 JVMTI 分配的 signature、class array 和本地引用，最后 `DisposeEnvironment()`。

一次 batch 调用避免 Host 把部分成功误判为完整成功。首版只返回整体结果，同时在失败内容中记录 descriptor 或 JVMTI error code。

### 5.5 结果与状态提交

结果协议：

```text
OK\t<count>
ERROR\t<stage>\t<jvmtiError>\t<detail>
MISSING\t<descriptor>
UNMODIFIABLE\t<descriptor>
```

- Host 对 `result.txt` 做有上限轮询，建议总计 5 秒。
- `am attach-agent` 失败、超时、结果格式错误、目标类缺失或 JVMTI 返回错误均视为部署失败。
- 只有 `OK` 才返回 `LaunchResult.success=true`，随后沿用现有 `deployFileManager.commit()` 和 deploy history 更新。
- 本路径不修改 `.overlay`，因此返回并保留当前 package 的既有 overlay id，不能写成空值覆盖可信 checkpoint。
- 失败时不提交部署历史；保留本次请求目录用于日志采集，下一次请求清理更旧的成功目录。

### 5.6 Activity 生命周期

首版语义等同“在线替换类实现”，**不自动重建 Activity**：

- 已存在对象下一次调用被替换的方法时立即执行新实现。
- 仅在 `onCreate()` 等生命周期入口读取新逻辑的页面，需要用户重新进入页面。
- 若要求严格保持当前 `APPLY_CHANGES_AND_RESTART_ACTIVITY` 体验，需要新增独立的进程内 Activity relaunch 能力；该能力不应混入首版 redefine 验证。

## 6. 失败与降级

| 失败阶段 | 首版行为 | 原因 |
|---|---|---|
| root 或 `run-as` 状态不满足 | 不命中自有路径，保留原路径 | 不扩大设备范围 |
| 系统应用命中，但 app 未运行 | 明确提示在线 Hot Reload 需要运行中的主进程 | `RedefineClasses` 需要目标 VM |
| agent/root staging 失败 | 部署失败，保留历史 | 防止伪造成功 |
| 类未加载或不可修改 | 部署失败并报告类名 | 不主动加载类 |
| JVMTI redefine 失败 | 部署失败并报告 error code | 保留真实失败边界 |
| attach 成功但结果超时 | 部署失败，采集 agent/logcat 证据 | attach 返回不代表 redefine 成功 |

后续可增加“root overlay + 进程重启”作为系统应用 Hot Fix 降级，但它需要同时接管 AS startup agent 和 overlay 写入，不属于首版在线 Hot Reload 的最小改动。

## 7. 预计改动文件

### 7.1 生产代码

| 文件 | 预计改动 |
|---|---|
| `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt` | 在 AS `fullSwap/codeSwap` 前按 package 尝试 root Hot Reload；成功时保留当前 overlay id |
| `idea/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/RootJvmtiHotReloadTransport.kt`（新增） | 集中管理命中条件、主进程检查、调用 writer 和结果到 deploy result 的转换 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/RootHotReloadWriter.kt`（新增） | 构建请求、push zip、root 解压/权限修复、执行 attach、轮询和解析结果 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManager.kt` | 增加 root 模式的 bundle/agent 准备能力；原 `run-as` 路径保持不变 |
| `jvmti_agent/src/main/cpp/native-lib.cpp` | 增加 `jugg_hot_reload:` options 分派，保持 startup 分支不变 |
| `jvmti_agent/src/main/cpp/class_redefiner.h`（新增） | 声明请求处理和 class redefine 入口 |
| `jvmti_agent/src/main/cpp/class_redefiner.cc`（新增） | 请求校验、loaded class 匹配、batch `RedefineClasses`、原子结果写入 |
| `jvmti_agent/CMakeLists.txt` | 编译新增 native 文件 |
| `build.gradle` | 递增 `agentVersion` |

`JuggDeployData.kt` 和 `DeployDataGenerator.kt` 预计无需修改：当前数据模型和分类已提供所需 descriptor 与单类 Dex。

### 7.2 测试与验证代码

| 文件 | 预计改动 |
|---|---|
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/hotreload/RootHotReloadWriterTest.kt`（新增） | 请求格式、路径安全、位数选择、root 脚本、结果解析、超时和失败不成功 |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperDeployFlowTest.kt` | 增加 root 系统应用纯 Hot Reload 路由与 deploy history 行为 |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/deployflow/VirtualDeployDevice.kt` | 模拟 root uid、run-as 失败、attach-agent 和 result 文件 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManagerTest.kt` | 覆盖 root agent 准备；现有普通 `run-as` 行为必须保持 |

不计划增加只 mock `jvmtiEnv` 的 native 单元测试：它无法证明 ART 接受实际 Dex。native redefine 以真机 L3 为行为 owner。

### 7.3 实现完成后同步文档

- `docs/ai_knowledge/03_runtime_jvmti.md`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/wiki/zh/concepts/jugg-jvmti-agent.md` 与英文镜像
- `docs/wiki/zh/concepts/apply-changes.md` 与英文镜像
- `docs/wiki/zh/capabilities/deploy/hot-reload.md` 与英文镜像

## 8. 测试矩阵

| 层级 | Owner / 路径 | 修改前预期 | 修改后预期 |
|---|---|---|---|
| L1 | `RootHotReloadWriterTest` | 无自有请求与结果协议 | 生成安全请求；仅 `OK` 成功；超时/错误失败 |
| L1 | `JuggJvmtiAgentManagerTest` | root 系统应用无法准备 agent | root 写入 agent，普通 run-as 路径不变 |
| L2 | `JuggDeployerHelperDeployFlowTest` | root + run-as 失败进入 AS Deployer 并失败 | 纯 Hot Reload 进入自有 transport，不调用 AS swap，成功后提交历史并保留 overlay id |
| L2 | 同上 | 普通设备走原 Apply Changes | 未命中 root 条件时仍调用原路径 |
| L3 | 报告设备/等价 root 系统应用 | `Deploy Changes` errorId 34 | 已加载 Activity 方法体在线生效，进程 PID 不变 |
| L3 | 同一设备 | 未加载类/结构变化无明确自有边界 | 返回目标类或不支持原因，不提交部署历史 |

## 9. 实施顺序

1. **Phase 0 真机可行性门禁**：手工放置最小 agent，验证 `am attach-agent`、options、结果文件、同 agent 重复 attach、SELinux 和 UID 1000 行为。
2. 先增加 L1 请求/结果协议测试，确认修改前因能力缺失而失败。
3. 实现 `RootHotReloadWriter` 与 `JuggJvmtiAgentManager` root agent 准备。
4. 实现 native request handler 和 batch redefine，递增 `agentVersion`。
5. 增加 L2 Flow case，再在 `JuggDeployTask` 接入路由。
6. 执行定向 L1/L2、`:idea:compileKotlin`、agent bundle 构建。
7. 在报告设备执行 L3：修改普通 Activity 已加载方法，确认新行为生效且 PID 不变。
8. 同步 ai_knowledge 和中英文 Wiki。

若 Phase 0 证明该 ROM 禁止 `am attach-agent`，停止后续实现并回到“root overlay + 重启”方案，不为不可用能力继续增加协议和抽象。

## 10. 验收标准

- 报告设备修改普通 Activity 的已加载方法后，Jugg 显示 HOT_RELOAD 成功，App PID 不变，新方法实现被执行。
- 日志明确显示命中 root Hot Reload、自有 request id、目标类数量和 redefine 结果。
- 整条成功路径不调用 AS `optimisticSwap`、`overlayInstall` 或 install-server。
- agent 未写 `OK` 时绝不提交 deploy history。
- 普通 debuggable 应用、root 但 `run-as` 可用设备、非纯代码 payload 的现有行为不变。
- 结构变化、新类、未加载类和多进程边界均有明确失败或原路径行为，不伪造成功。

## 11. 待确认事项

1. 首版是否接受“在线 redefine，但不自动重建 Activity”的语义。
2. Phase 0 后决定动态 attach 是否必须使用独立 so 副本。
3. root 系统应用 Hot Fix/资源变更是否另开后续方案，实现 root overlay + 重启降级。
