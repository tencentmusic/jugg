# Standalone Deployer 自有 API 与 Base API 冲突收敛方案

## 1. 背景

`standalone_deployer` 为 Java 11 standalone runtime 提供固定 Quail deployer、真实 ddmlib 和 shaded protobuf 实现。`platform_compat/base_api` 同时保存了一组用于脱离 Android Studio 编译的 `com.android.*` Stub，并被 `cmd_line` 打入生产发行物。

当前发行物因此存在同名 class 的多个 owner：

- `com.android.ddmlib.IDevice`：`base_api.jar` 与真实 ddmlib。
- `com.android.tools.deployer.model.Apk` / `ApkEntry` / `DexClass`：`base_api.jar` 与 `standalone_deployer.jar`。
- `com.android.tools.idea.protobuf.ByteString`：`base_api.jar` 与 `studio-proto.jar`。

`jugg-standalone` 目前通过把 `base_api.jar` 放到 classpath 末尾规避错误加载。这只能依赖顺序维持正确 owner，无法消除重复 class，也会让未来 ddmlib / deployer 的破坏式 API 更新传播到共享调用链。

## 2. 已确认决策

1. 不修改搬运的 `com.android.tools.deployer.*` Quail 源码包名。
2. 共享边界使用 Jugg 自有 API；类名和当前已依赖调用面保持不变，迁移调用点原则上只修改 import。
3. 不在本次任务调整类命名；后续如需改为 `IJuggDevice` 等名称，单独提交。
4. 外部类型与自有类型只在 IDEA compat / standalone deployer adapter 边界转换。
5. `base_api` 删除全部 `com.android.*` Stub，保留 CLI 运行时确实需要且没有真实 provider 的 `com.intellij.*` 与 `org.apache.log4j.*` 实现。
6. 不新增 `cli_runtime_stub` 模块；清理后的 `base_api` 直接承担 CLI runtime stub 职责。
7. 不引入独立进程、RPC、Shadow relocation 或 child-first ClassLoader。

## 3. 目标

- `base_api.jar` 不再包含任何 `com/android/**` class。
- CLI 生产 classpath 中关键 Android class 只有一个 owner。
- 共享部署数据与执行接口不再暴露 ddmlib、Quail deployer 或 shaded protobuf 类型。
- 现有部署调用表达式尽可能保持不变，主要 diff 为 import 和边界转换。
- IDEA 与 standalone 的 install、APK parse、overlay、Apply Changes 行为保持不变。
- 删除 `jugg-standalone` 对 `base_api.jar` 顺序的依赖。

## 4. 非目标

- 不重命名自有 `IDevice`、`Apk`、`ApkEntry` 等类。
- 不修改 deploy recover / retry / Direct Overlay 状态机。
- 不改变 deployment cache、overlay id、install mode 或错误语义。
- 不迁移 Android Studio 的 Run Configuration、device selection、debug attach 等 IDE 专属 API。
- 不为未来未确认能力扩展自有类型字段或通用转换框架。

## 5. 自有 API 边界

### 5.1 代码位置

自有 API 放在 `deploy_compat/interface`，该模块已经是 IDEA compat、`main` 与 standalone deployer 的共享契约模块，不新增额外模块。

建议使用统一包：

```text
com.sickworm.intellij.jugg.deploy.api
```

### 5.2 类型映射

| 外部类型 | Jugg 自有类型 | 最小兼容面 |
|---|---|---|
| `com.android.ddmlib.IDevice` | `deploy.api.IDevice` | 只保留当前调用使用的属性、方法和嵌套枚举 |
| `com.android.tools.deployer.model.Apk` | `deploy.api.Apk` | 保持当前字段名和只读语义 |
| `com.android.tools.deployer.model.ApkEntry` | `deploy.api.ApkEntry` | 保持 name/checksum/apk/qualifiedPath 调用面 |
| `com.android.tools.deployer.model.DexClass` | `deploy.api.DexClass` | 保持 name/checksum/code/dex，并保留 D8 已产出的字段重初始化状态 |
| `com.android.tools.idea.protobuf.ByteString` | `deploy.api.ByteString` | `copyFrom(byte[])`、不可变 bytes、边界转 protobuf |
| `DexComparator.ChangedClasses` | `deploy.api.DexComparator.ChangedClasses` | new/modified class 列表 |
| `Deploy.Arch` | `deploy.api.Deploy.Arch` | `ARCH_UNKNOWN` / `ARCH_32_BIT` / `ARCH_64_BIT` |
| `com.android.utils.ILogger` | `deploy.api.ILogger` | 只保留现有 executor 使用的日志调用面 |

自有类型禁止在字段、参数、返回值、父类或泛型中继续暴露 `com.android.*` 类型，也不增加公开 `raw: Any` 作为通用逃生口。已有 runtime wrapper 中的 raw 对象保持现状，仅由对应 executor/compat 内部消费；本次不扩大 raw 暴露范围。

### 5.3 兼容原则

- 保持类名、成员名和已使用调用表达式，业务文件优先只替换 import。
- 只实现编译和现有行为所需 API；禁止镜像完整 ddmlib / deployer API。
- 设备 identity、`equals` / `hashCode`、serial 和 name 语义必须与迁移前一致。
- 外部 enum 或 error ordinal 不持久化到新增自有数据结构。
- D8 当前会生成并由 `OptimisticApkSwapper` 消费字段重初始化状态，因此该状态属于现有 Apply Changes 契约，不是面向未来的预留字段。

## 6. 边界转换

### 6.1 IDEA / Android Studio

- `deploy_compat/v_*` 和 IDEA adapter 可以继续依赖当前 Android Studio/ddmlib 类型。
- 进入共享接口前把 raw device、APK model、protobuf content 和 arch 转成自有类型。
- 调用 Android Studio deployer 前再转回对应版本的 raw 类型。
- IDE-only API 继续保留在 `IAsDeployerCompat`，只替换其中 Apply Changes 共享调用面的数据类型。
- Debugger `ClassRedefiner` 继续使用现有 `JuggClassRedefiner` wrapper，本次不改 debugger attach 生命周期。
- 设备、APK、install session、overlay、cache、redefiner 等带 runtime owner 的调用只使用选中的 priority compat；priority 出现兼容错误时原样抛出，禁止把一个版本创建的 wrapper 传给另一版本实现。
- 仅 `getInstallMode`、运行配置建议和模块信息等不消费 runtime owner 对象的纯查询保留跨版本 fallback。

### 6.2 Standalone

- `StandaloneApplyChangesExecutor` 实现自有 `IApplyChangesExecutor`。
- ddmlib `IDevice`、Quail `Apk` / `ApkEntry` / `DexClass`、protobuf `ByteString` 和 `Deploy.Arch` 只出现在 standalone boundary converter 与搬运实现内部。
- 转换逻辑保持局部私有；不新增通用 converter interface。
- `StandaloneApplyChangesExecutor` 只公开 `IApplyChangesExecutor` 生产入口，不增加转发内部 converter 的 package-private 方法。

## 7. Base API 收敛

删除 `platform_compat/base_api/src/main/java/com/android/**`：

- `ddmlib/IDevice.java`
- `ddmlib/IShellEnabledDevice.java`
- `tools/deployer/ZipUtils.java`
- `tools/deployer/model/Apk.java`
- `tools/deployer/model/ApkEntry.java`
- `tools/deployer/model/DexClass.java`
- `tools/idea/protobuf/ByteString.java`

`ZipUtils` 当前没有生产字节码引用，直接删除，不创建自有替代类型。

清理后 `base_api` 继续保留：

- CLI 实际使用的 `com.intellij.*` 最小实现。
- CLI 实际使用的 `org.apache.log4j.*` 最小实现。

`cmd_line` 仍可打包 `base_api.jar`，但不再需要为 `jugg-standalone` 单独调整其 classpath 顺序。

## 8. 实施顺序

### 8.1 失败证据与架构守卫

先在正确 owner 中增加并确认失败：

1. 扩展 `DeployCompatArchitectureTest`：扫描 interface JAR 的全部 class，除既有 IDE-only debugger helper 精确 allowlist 外，禁止引用任何 `com/android/**` 类型。
2. 扩展 `StandaloneDeployerArchitectureTest` 或增加同级发行物守卫：`base_api` 禁止包含 `com/android/**`。
3. 生产发行物关键 class owner 唯一：`IDevice` 归 ddmlib，Quail model 归 standalone deployer，`ByteString` 归 studio proto。

### 8.2 自有类型

在 `deploy_compat/interface` 增加最小自有类型及英文介绍性注释，公共 API 保持 Java/Kotlin 互操作。

### 8.3 调用点迁移

按以下顺序迁移，避免同时修改行为：

1. `main` 数据模型和平台契约。
2. `deploy_compat/interface` 的 Apply Changes 契约。
3. IDEA compat / adapter 转换。
4. standalone executor 转换。
5. `cmd_line` 和测试 fixture。

调用点迁移提交中禁止混入命名清理、格式化或部署逻辑重构。

### 8.4 Base API 与发行物

1. 删除 `base_api` 的 Android Stub。
2. 删除 `cmdline-distribution.gradle` 中的 `base_api.jar` 重排。
3. 构建发行物并检查 class owner。

### 8.5 文档同步

同步：

- `docs/ai_knowledge/01_architecture.md`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/ai_knowledge/04_engineering_compat.md`
- `docs/ai_knowledge/98_code_map.md`

## 9. 测试价值与 owner

| 验证 | 价值判断 | Owner / 层级 |
|---|---|---|
| 共享 API 不泄漏外部 Android 类型 | 稳定架构契约，新增 | `DeployCompatArchitectureTest` / 静态架构守卫 |
| `base_api` 不包含 `com/android/**` | 稳定发行边界，新增 | `StandaloneDeployerArchitectureTest` / 静态架构守卫 |
| 自有 model 与 Quail model 转换 | 确定性协议转换，新增或并入现有 owner | `StandaloneApplyChangesExecutorTest` / L1 |
| D8 字段重初始化状态往返 | 当前 swap 行为依赖，新增 | `StandaloneApplyChangesExecutorTest` / L1 |
| 设备 adapter 的 identity、name、serial、API、ABI | 外部兼容契约，新增或并入 host adapter owner | adapter test / L1-L2 |
| owner-bound compat 不跨版本 fallback | 防止稳定的类型 owner 错配，新增 | `AsDeployerCompatDispatcherTest` / L2 |
| install / overlay / swap 行为 | 已有行为，复用 | `JuggDeployerInstallTest` / L2 |
| standalone resource / executor | 已有行为，复用 | standalone 定向测试 / L1-L2 |
| Run → deploy 主链路 | deploy 编排回归，复用 | `TopLevelFlowTest#testInstallAndLaunch`、`#testDeploy` / L3 |
| 既有 CLI 行为 | 共享模块发行回归，复用 | `CmdLineTest` |

不为简单字段、getter、原样转换或 import 迁移新增测试。转换测试只断言稳定可观察的数据与 identity 语义，不断言私有 converter 调用。

## 10. 验证命令

定向执行，禁止无过滤全量 `:main:test` / `:idea:test`：

```text
./gradlew :idea:test --tests com.sickworm.intellij.jugg.deploy.run.DeployCompatArchitectureTest
./gradlew :idea:test --tests com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployerInstallTest
./gradlew :idea:test --tests com.sickworm.intellij.jugg.manager.TopLevelFlowTest.testInstallAndLaunch --tests com.sickworm.intellij.jugg.manager.TopLevelFlowTest.testDeploy
./gradlew :standalone_deployer:test --tests com.sickworm.intellij.jugg.deploy.run.StandaloneApplyChangesExecutorTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerArchitectureTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerResourceTest
./gradlew :cmd_line:test --tests com.sickworm.intellij.jugg.cmdline.CmdLineTest
./gradlew :idea:compileKotlin :cmd_line:compileKotlin :standalone_deployer:compileJava :cmd_line:installDist
```

额外检查：

- 使用 Java 11 启动 distribution 内 `cmd_line` 与 `jugg-standalone`。
- 扫描 `jugg-standalone` 实际 classpath，关键 Android class 必须只有一个 owner。
- `git diff --check`。
- 检查本次 diff 中日志格式，禁止无关格式化。

## 11. 完成标准

- `base_api` 源码和 JAR 中不存在 `com/android/**`。
- `jugg-standalone` 不再依赖 classpath 顺序覆盖 Android Stub。
- 共享 Apply Changes API 只使用 JDK、Kotlin 和 Jugg 自有类型。
- 业务调用表达式除必要 import 外无行为变化。
- IDEA、standalone、CLI 定向回归通过。
- 文档与代码一致，改动按 AGENTS.md 提交规范完成独立 commit。
