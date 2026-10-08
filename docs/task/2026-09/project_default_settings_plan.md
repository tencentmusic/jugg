# 工程默认设置适配方案

## 目标与边界

在 IntelliJ/Android Studio 插件收到 `ProjectCustomConfig` 后，按工程保存的已应用版本，一次性应用后台下发的基础类型默认设置。版本记录使用 `PropertiesComponent.getInstance(project)`；`JuggSettings` 当前全局设置存储范围本次保持不变。

不新增 `ProjectDefaultSettings` 数据类，不把默认设置变成持续强制策略，也不改变升级通知、其他自定义配置字段、业务消费逻辑和 `CustomConfigManager.updateDefaultConfig()` 的既有行为。

## 现状依据

- `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt` 中 `ProjectCustomConfig` 是 `/check_update` 的反序列化模型，目前缺少 `defaultSettingsVersion` 与 `defaultSettings`。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/CheckUpdateHandler.kt` 在收到配置后仅调用 `customConfigManager.updateDefaultConfig(it)`。
- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt` 的属性通过无 Project 参数的 `PropertiesComponent.getInstance()` 持久化。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` 已提供 `refreshSettings()`，宿主侧 `JuggControlPanelHost.refresh(project)` 可刷新 Control Panel。

## 实现步骤

### 1. 扩展协议模型

在 `ProjectCustomConfig` 增加：

- `defaultSettingsVersion: Int? = null`
- `defaultSettings: Map<String, Any?>? = null`

使用默认值保持旧服务端响应兼容。Gson 将 `Map<String, Any?>` 中的 JSON 数字通常反序列化为 `Double`；应用时须结合目标属性类型检查并做安全数字转换，不做字符串到数字或 Boolean 的宽松转换。

### 2. 在默认设置应用器内动态匹配属性

在 `ProjectDefaultSettingsApplier` 内使用 Kotlin 反射按属性名匹配 `JuggSettings` 的公开可写属性，读取目标属性声明类型，再校验、转换并通过属性 setter 写入。支持现有设置委托已使用的基础类型 `Boolean`、`Int`、`Long`、`Float`、`String`；其他目标类型暂不处理。数字只接受 JSON 数字，转换时检查有限值、整数性、范围及精度，拒绝溢出或有损转换；`null` 值跳过，因为当前 String 委托不保留 nullable 属性的 null 读取语义。未知 key、只读属性、不支持的目标类型及错误值分别跳过；不能按 `jugg.<key>` 直接写 `PropertiesComponent`，否则会绕过属性的自定义持久化 key 和 setter。每个成功写入的 key 和每个未匹配、类型错误或 setter 异常的 key 均打印 debug 日志；setter 异常继续向外抛出，确保不保存版本。无需为每个设置同步维护白名单或 `when` 分支。当前构建已经依赖 `kotlin-reflect`（`main/build.gradle`、`idea/build.gradle`），无需新增依赖。

这一选择意味着所有公开、可写且类型受支持的 `JuggSettings` 属性（包括以后新增的）都可能被后台按名称下发，不限于下列 12 个。后台由同一维护者管理，视作受信任配置源；如果有不允许远程改变的属性，应在实施前收窄属性可见性或另行确定排除规则。反射结果只从公开属性获取，不能匹配私有字段或任意对象成员；匹配范围与混淆后的属性名一致性需以插件实际打包配置和定向测试验证。

首期明确验收的 12 个 Boolean key（不是实现白名单）：

`compileOnSave`、`deployOnSave`、`isConfirmFallbackWhenNoFileChanges`、`isConfirmFallbackWhenTooManyChanges`、`isAlwaysRestartAppAfterDeployment`、`isAutoFallbackToGradleWhenDeployError`、`isEmbeddedToApk`、`isCheckChecksumWhenFileChanges`、`isEnableDirectOverlayDeploy`、`isUseProjectKotlinCompiler`、`isEnableBackupClasspath`、`isIgnoreWontCompileModules`。

代码检索确认当前 `JuggSettings` 尚无 `isConfirmFallbackWhenTooManyChanges` 属性。本次仅补齐同名可写属性供下发设置使用，不改变“变更过多”的现有确认行为；未来是否消费该属性另开任务。

### 3. 新增最小默认设置应用器

建议在 `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/` 增加一个职责单一的应用器（例如 `ProjectDefaultSettingsApplier`），由 `CheckUpdateHandler` 持有并调用。它直接接收 `Project`、`ProjectCustomConfig` 和日志，使用项目级 `PropertiesComponent` 保存版本；不引入测试专用 provider/supplier/lambda。

处理顺序：

1. 版本或 Map 任一为 `null`，立即返回。
2. 读取项目级已应用版本；相等则立即返回。
3. 调用 `JuggSettings` 的动态应用方法，逐项处理 Map；未知 key 和类型错误只影响自身。
4. 所有字段处理成功后，再写入项目级版本。
5. 任一设置写入抛出异常时，不写入版本，保留异常并记录 warn，使下次刷新可重试。

建议使用单一版本 key 前缀，例如 `jugg.defaultSettingsVersion`，但通过 `PropertiesComponent.getInstance(project)` 获取组件，确保不同工程隔离。不要用服务端版本大小比较，必须使用“不相等”。

### 4. 接入 CheckUpdateHandler

保持现有顺序：先执行 `customConfigManager.updateDefaultConfig()`，随后调用默认设置应用器。应用器失败不能阻断升级通知、普通通知或其他配置更新；异常应在应用器边界收口并保留重试所需的未保存版本状态。

设置写入成功并保存版本后，由 `JuggManager` 传入实际业务使用的刷新回调，调用当前工程 `JuggControlPanelController.refreshSettings()`；不经 `JuggControlPanelHost.refresh(project)`，避免切换页面。UI 刷新失败仅记录 warn，不把已经成功应用的设置视为失败；设置写入失败则不保存版本。

## TDD 与测试落点

先在 `idea/src/test/java/com/sickworm/intellij/jugg/ide/ui/` 为默认设置应用行为增加失败测试，再实现生产代码。测试价值门禁通过：这些用例保护版本语义、配置容错、工程隔离和失败重试等稳定可观察行为，属于 L2（IDE Project/PropertiesComponent 与设置编排协作）。

至少覆盖：首次应用、同版本保留用户修改、升级和回滚重应用、缺 key 保留旧值、未知 key 忽略、单字段类型错误不影响合法字段、版本或 Map 为 null 不应用、不同 Project 版本隔离、应用异常不保存版本。增加逐 key 参数化断言，确保 12 个验收 key 均真正影响对应属性，并验证清单外已有的 Boolean、Int、Long、String 属性可动态匹配，以及 JSON 数字的安全转换、越界或有损输入跳过、null 值不覆盖现值；当前没有公开 Float 设置，不为测试添加生产字段，Float 分支以编译和类型检查验证；不扫描源码或用反射锁定私有方法。测试不应只断言内部调用次数；应断言 `JuggSettings` 的结果、项目版本持久化结果和异常后的重试状态。必要的 IntelliJ Project/PropertiesComponent 测试基建应复用现有 `TestGlobal`/LightProject 设施，不新增生产 mock seam。

可另在 `CheckUpdateHandler` 现有测试 owner（若不存在则新增最小 L2 owner）补一条编排测试，证明 `updateDefaultConfig()` 仍先执行且默认设置随后执行；其余字段行为沿用已有覆盖或通过定向编译验证。

## 验证与交付

- 先运行新增定向测试，确认实现前失败、实现后通过。
- 执行 `./gradlew :idea:compileKotlin`。
- 按需要执行 `./gradlew :idea:test --tests '<新增测试类>'`，禁止无过滤全量测试。
- 检查 `git diff --check`，核对日志格式与英文代码注释。
- 修改文件预计包括：`main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt`（协议字段）、`main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`（动态匹配及缺失设置）、`idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/CheckUpdateHandler.kt`（接线）、`idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`（传入已有的无切页设置刷新回调）及对应定向测试；新增 `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ProjectDefaultSettingsApplier.kt`（项目级版本与应用顺序）。业务消费文件明确不在范围内。
- 插件代码或打包产物发生变更后需要重新安装插件才能在 IDE 中验证；仅文档方案本身不需要安装。
