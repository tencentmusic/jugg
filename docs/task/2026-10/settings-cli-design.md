# Settings CLI 设计方案

> Purpose: Expose all configurable global Jugg settings through their existing property names and types.
> Decision: Share setting metadata, parsing, confirmation rules, and mutation behavior between IDE and CLI. Use automatic Runtime routing; require explicit confirm=true for guarded changes. The revised scope is approved and implemented in an isolated worktree.
> Impact: Extend shared settings, IDE adapters, MCP/CLI, and the existing project-state invalidation boundaries. The implementation adds global settings editing and shared confirmation, with CI continuation policy isolated from persisted thresholds.

状态：已批准并完成实现与审查。创建日期：2026-10-26；修订日期：2026-10-27。用户已明确批准本版方案及 CI/输出调整。

## 1. 本轮决策

用户已确认全局 Settings 范围，并要求以下调整：

1. key 直接使用现有 camelCase 属性名，不增加别名映射。
2. 覆盖所有设置，按实际类型解析；解析或业务校验失败报错。
3. 明确全局设置与 Runtime 的关系，日常操作无需指定 Runtime。
4. IDE 和 CLI 共用描述、类型、确认条件与提示，并共用修改流程。
5. 需要确认的修改必须收到 `confirm=true`；未提供或为 false 时返回错误及当前 IDE 的提示。
6. 编译阈值覆盖属于 CI 的 `buildIncrementalApk` 路径。CI 应通过 CompileUiHandler 定制超阈值行为，移除全局阈值赋值。
7. list 每项包含 key、value、description；get/set 不返回 description。

本版取消“只开放 7 个 boolean”的限制，Backup classpath、Custom Server、数值及结构化设置一并纳入。继续提供 `list / get / set / reset`。项目 Run Configuration 的增删改、批量导入导出、全量 reset 和安装/清缓存等操作不在本次范围内。

## 2. Runtime 与全局设置

**持久化设置属于用户级全局配置，默认存储在 `~/.jugg/settings.json`。IDEA 与 standalone 使用相同全局根目录时，修改的是同一份配置。**

Runtime 是执行 Kotlin 设置逻辑的进程。现有 CLI 是 Python MCP 客户端，设置的类型、确认规则、存储锁和关联行为位于 Kotlin 层，所以 CLI 需要连接一个 Runtime 来执行请求。

`--runtime idea|standalone` 的唯一用途是显式选择处理请求的进程，便于诊断或要求特定 Host。它不是 settings 的作用域参数，**无需指定**。CLI 自动发现可用 Runtime；优先复用匹配当前项目的进程，其次选择其他支持 settings 的进程；均不存在时启动无项目 standalone。文档主示例均省略 `--runtime`。

读取配置值和写入结果应与所选 Host 无关；进程覆盖、平台能力和版本差异单独作为诊断信息返回。响应保留 `scope=global`、实际 `settingsPath`、执行进程类型/版本，不增加 Host 专属 settings 文件。

`--project-dir` 可用于优先选中对应项目的进程，但保存仍为全局。settings 不需要设备、Gradle baseline 或项目初始化。`--serial`、`--if-compiling` 不适用，显式提供时报参数错误。

## 3. 设置范围与类型

所有可配置项在共享定义中登记，直接使用属性名。通过 Kotlin 属性引用/委托取得 key 和类型，避免另维护一套可漂移的字符串名。注册不是 CLI 专属白名单；IDE 也消费同一份定义。

### 3.1 当前可修改设置清单

| 属性名 | 类型 | 处理方式 |
|---|---|---|
| `compileOnSave`、`deployOnSave` | Boolean | 保存全局选项；描述中标出当前 Host 的消费能力 |
| `isConfirmFallbackWhenNoFileChanges`、`isConfirmFallbackWhenTooManyChanges` | Boolean | 复用现有默认值和回退确认语义 |
| `isAlwaysRestartAppAfterDeployment`、`isAutoFallbackToGradleWhenDeployError`、`isEmbeddedToApk` | Boolean | 共用部署行为设置；嵌入 APK 的确认条件见第 5 节 |
| `isCheckChecksumWhenFileChanges`、`isEnableDirectOverlayDeploy`、`isUseProjectKotlinCompiler` | Boolean | 共用现有业务设置 |
| `isEnableBackupClasspath` | Boolean | 需要确认，记录部署历史失效要求 |
| `isIgnoreWontCompileModules` | Boolean | 保存全局选项 |
| `maxCompileSourceModules`、`maxCompileSourceFilePoints` | Int | 严格整数解析，作为普通全局设置持久化；CI 通过 handler 继续编译，不赋值覆盖阈值 |
| `isEnableWarmUp`、`isEnableConstRefTasks` | Boolean | 增加持久化配置层，保留测试/内部调用的进程覆盖语义 |
| `serverUrl` | String? | 复用 Custom Server 的设置/恢复自动选择行为，关联字段共同提交 |
| `serverExpireTimeMill` | Long | 高级设置，严格 Long 解析；校验与 URL 的组合，进入自定义服务模式同样要求信任确认 |
| `deviceCompatRecordJson` | String，内容为兼容记录 JSON | 使用现有 CompatDeployRecord 模型校验，保存后记录重新安装要求 |
| `sliceDeployRecordJson` | String，内容为分片记录 JSON | 使用现有 SliceDeployRecord 模型校验，要求分片大小有效 |
| `defaultCompileSettings` | RunConfigurationTemplate | 按现有结构化类型解析和序列化，仍使用原有内部 JSON 存储 |

共 21 个可配置属性。默认值来自代码定义，不在 Python 或 IDE 重写。两个 Int 阈值增加普通持久化支持，同时移除 CI 中的 Int.MAX_VALUE 赋值，不为它们建立进程覆盖层。另两个原进程选项 isEnableWarmUp/isEnableConstRefTasks 保留此前设计的全局配置与内部进程赋值分离，避免测试关闭 warm-up 写入用户文件。阈值测试使用隔离设置根目录，按持久化设置的正常入口调整并恢复。

### 3.2 解析规则

CLI 将命令行 value 原文交给 Kotlin，共享层根据目标属性类型解析。MCP `value` 为文本字符串，`confirm` 为 JSON boolean；IDE 的控件值通过同一类型定义编码后提交到同一修改入口。

| 目标类型 | 解析约定 |
|---|---|
| Boolean | 只接受 `true` / `false`，拒绝 `yes`、`1` 等隐式转换 |
| Int / Long | 十进制整数；拒绝小数、非数字及溢出；之后校验该设置的业务范围 |
| String | 保留文本；仅在既有业务本身要求时 trim，例如 serverUrl |
| String? | 额外接受文本 `null` 表示空值；需要字面字符串 null 时使用 JSON 字符串形式 `"null"` |
| JSON 内容字符串 | 先按 String 处理，再用现有记录类型校验内容；空串沿用既有“无记录”约定 |
| RunConfigurationTemplate | JSON object；校验字段类型、非空字段、数值范围及已有业务约束，拒绝未知字段和隐式类型强转 |

当前不存在的类型不预先实现解析框架。未来新增设置时必须给出对应类型的解析/校验定义；不支持的类型明确报错。

未知 key、类型错误、非法枚举/字段组合、越界值均返回 `INVALID_PARAMS`，包括 key、expectedType 和可定位的字段路径。解析/校验失败时不写文件、不修改内存、不执行关联动作。

## 4. 命令与结果

```bash
jugg settings list
jugg settings get isEnableDirectOverlayDeploy
jugg settings set isEnableDirectOverlayDeploy false
jugg settings set maxCompileSourceModules 30
jugg settings set serverUrl https://example.com --confirm true
jugg settings set isEnableBackupClasspath true --confirm true
jugg settings reset isEnableBackupClasspath --confirm true
jugg settings get defaultCompileSettings
jugg --console=json settings list
```

`jugg settings` 等同 list；help 本地执行。`--confirm true|false` 必须显式携带值，不能只写 `--confirm`，不引入 `--yes` 或交互式终端确认。

输出按命令拆分，保持简洁：

| 命令 | data 中的设置结果 |
|---|---|
| list | items，每项仅 key、value、description；description 来自 IDE/CLI 共用定义 |
| get | key、value，不返回 description |
| set / reset | key、previousValue、value、changed，不返回 description；存在关联动作时另附后续效果信息 |

value 表示全局配置值，保持实际 JSON 类型。type/default/可写性/确认规则继续由共享定义维护，用于校验和 IDE 展示，不扩充默认命令输出。scope/settingsPath/执行 Runtime 等上下文位于响应公共部分。确认错误仍返回 confirmation 提示，它独立于 description。

set/reset 返回 key、previousValue、value、changed 及必要的后续效果说明。需要确认的请求先通过确认门禁，再判定同值写入；同值成功返回 changed=false，不重复安排清历史等效果。reset 使用该版本默认值并走相同校验、确认和应用流程，显式保存默认值以避免 legacy migration 将被删除字段重新填回。

JSON 继续使用 structuredContent，stdout 只输出一个 JSON 对象；失败退出码非零，Ctrl-C 沿用 130。文本输出显示共享提示和全局作用范围。

## 5. IDE 与 CLI 共用确认

共享定义提供 `confirmation(currentValue, requestedValue, relatedSettings)`，返回标题、正文段落及按钮文案，或表示不需要确认。确认条件和正文只维护一份。IDE 转为原有对话框展示，CLI 使用纯文本，HTML 仅为渲染形式。

| 设置/触发条件 | 复用的现有 IDE 提示 |
|---|---|
| `isEmbeddedToApk` 从 false 改为 true | 标题 `Enable Embedded to APK`；正文 `This will embed incremental changes to APK, let incremental effects for Android RemoteViews, but it will cost more time to deploy.` 以及 `Are you sure to enable?` |
| `isEnableBackupClasspath` 的 set/reset | 标题 `Confirm Switch Backup Classpath`；正文 `This will effects compilation stability. Continue?` |
| `serverUrl` 设置非空自定义地址；或修改 `serverExpireTimeMill` 使现有地址进入自定义服务模式 | 标题 `Trust Custom Server?`；正文显示目标地址，以及现有关于更新 JAR、编译器 JAR 下载和加载的完整信任提示；按钮沿用 `Trust Server` / `Cancel` |

嵌入 APK 的关闭、清空 Custom Server 恢复自动选服，按现有 IDE 条件无需额外确认。设备兼容选项当前没有确认，也不额外添加。设置“未来 Run 是否弹回退确认”的开关本身不需要确认。

未提供 confirm 或 confirm=false：返回新错误码 `CONFIRMATION_REQUIRED`，message 包含共享正文，data.confirmation 包含 title/message/confirmLabel/cancelLabel；同时返回 key 和 `requiredArgument: {confirm: true}`。正文动态内容（例如 URL）按当前拟提交值生成，不缩略现有提示。

示例：

```json
{
  "status": "ERROR",
  "errorCode": "CONFIRMATION_REQUIRED",
  "message": "This will effects compilation stability. Continue?",
  "data": {
    "key": "isEnableBackupClasspath",
    "confirmation": {
      "title": "Confirm Switch Backup Classpath",
      "message": "This will effects compilation stability. Continue?"
    },
    "requiredArgument": {"confirm": true}
  }
}
```

该错误必须无任何写入/失效动作。CLI 不弹 IDE 对话框。IDE 首次提交得到同样的确认需求，用户点击确认后携带 confirm=true 再提交；取消则结束。正式提交在写锁内重新读取相关值并重新校验规则。若 IDE 展示后拟提交目标变化，则重新展示对应提示。

## 6. 共享实现与关联行为

### 6.1 定义和应用入口

新增一个小型 `JuggSettingDefinition` 类型，描述 key、实际类型、默认值、label、description、解析/业务校验和确认信息；定义集合放在 JuggSettings 邻近共享代码中。业务专用处理保留在既有 owner，不建立插件式设置框架。

新增具体类 `JuggSettingsEditor`，供 IDE 和 MCP 共用，其职责为读取/解析、校验、确认门禁、生成更新字段和提交。IDE Panel 不再硬编码这批描述；Controller 不再单独实现嵌入 APK / Backup classpath 的确认逻辑。Custom Server 的输入与信任提示也读取共享定义。

入口顺序：

```text
IDE 控件 / CLI 文本
  → 共享设置定义解析与校验
  → 共享确认条件（缺 confirm=true 则返回原 IDE 提示）
  → 既有业务 owner 生成关联更新
  → 全局锁内读最新状态、复核、原子写入
  → 更新内存，返回结果与后续效果
```

既有内部自动状态写入（如服务器自动切换、部署失败自动记录兼容设备）继续保留原调用语义；用户入口统一经过 Editor。内部更新不得绕过存储的缓存和原子性保证。

### 6.2 Custom Server

将 JuggServerChooser 中的“用户指定 URL / 恢复自动选服”的非 UI 规则抽成其可复用方法。UI、CLI 都调用这份逻辑。

设置非空 serverUrl：确认通过后，URL 与 `serverExpireTimeMill=-1` 一次原子提交。清空/reset：取消自定义覆盖，复用自动选服规则；有候选则选择，无候选则按既有行为保留可用状态，并返回实际选服结果。网络/候选探测在全局锁外进行，最终发布前复核用户模式，避免自动选择覆盖新提交的自定义服务。

`serverExpireTimeMill` 也按 Long 支持读写；-1 为自定义模式，0 为立即过期，正值为时间戳，其他负值拒绝。设置 -1 必须有有效 URL 并执行同一信任确认；它不能成为绕开 serverUrl 确认的第二个入口。

### 6.3 Backup classpath 和设备兼容记录

共享修改入口必须保留当前 IDE 的业务效果：Backup classpath 修改后部署历史失效；显式修改兼容记录后标记重新安装。避免 CLI 只写布尔值或 JSON，遗漏副作用。

因为设置全局共享而历史属于项目，建议统一采用“保存全局变更要求，各项目在下一次受锁保护的使用前应用”的方式。IDE 和 CLI 都显示这一生效时机，不在设置请求中遍历/初始化所有项目。

最小状态为两个内部变更序号：Backup classpath 历史失效序号、显式兼容记录重装序号，和设置值在同一次 JSON 提交中更新。每个项目在 `build/jugg/settings_applied.json` 保存已应用序号，避免切换 Runtime、重启或暂未打开的项目漏掉效果。序号是内部状态，不作为可编辑设置。

共享 `JuggSettingsProjectEffects` 在现有项目锁内比较序号，调用现有 `deleteDeployHistory()` / 持久化重装标记，并让 Host 清理相关内存上下文；成功后记录已应用序号。失败保留待应用要求并中止受影响的项目操作。首次部署时没有旧历史的项目直接对齐。既有项目在新增序号均为 0 时不失效；之后真实设置变更才触发。

Backup classpath 的历史清理在下一次基线使用前完成。兼容记录批量替换沿用当前 IDE 的项目级重装语义，不改为新的按应用精细失效系统。自动部署恢复产生的记录不重复安排用户设置效果。

这是本版相对初稿增加的行为范围：设置面板也从立即删除当前项目历史，统一为受项目锁保护的应用流程。它需要 L3 或等价 Flow 验证。设置请求成功表示值和后续要求均已持久化；项目失效/重装由之后的项目操作负责。

### 6.4 存储、缓存及进程覆盖

- Repository 增加严格读取和原子多字段更新；未变化与失败分开，异常保留 cause，其他字段原样保留。
- 用户写入成功后才更新内存。损坏文件明确报错且不覆盖；缺文件读取默认值且不创建文件。
- JuggSettings 在读取边界检测外部替换并刷新；显式 list/get 强制从磁盘读取。保持现有根目录切换及 reload 能力，不增加 watcher/广播协议。
- 文件身份、精确修改时间和大小用于检测原子替换；无法可靠判断时重新读取。验证同长度替换和读写交错，避免缓存把旧内容记为新版本。
- 两个 Int 阈值普通持久化，CI 取消写入；仅另外两个原进程选项保留配置值与进程覆盖的内部区分，业务读原属性得到有效值。命令的 value 返回配置值，内部状态不扩充 list/get 默认输出。无需为测试单独注入 supplier/provider。
- 元数据所需的关联读取从同一快照完成，尤其是 serverUrl 与过期标记。

### 6.5 CI 编译策略通过 CompileUiHandler 定制

**评估结论：可以复用现有 `confirmTooManyChanges(info)`，无需 CI 主动设置 maxCompileSourceModules 或 maxCompileSourceFilePoints。** 这里的 CI 特指 `BuildIncrementalApkCommand` 的两阶段构建路径，普通 `jugg compile/deploy` CLI 仍走各 Host 原有策略。

已核实的调用链：

- `BuildIncrementalApkCommand.compile()` 当前把两个阈值设为 Int.MAX_VALUE，随后直接调用 IncrementalCompilerHelper 并在最后 mergeDex。
- IncrementalCompilerHelper 在后续影响传播轮次检查 TooManyChanges；handler 返回 CONTINUE 后，已有 skipTooManyChangesCheck 状态让本次编译后续轮次继续，不必反复确认。
- 当前两个共享编译入口都先读取 isConfirmFallbackWhenTooManyChanges；为 false 时直接 FALLBACK，不调用 handler。因此仅增加 CI override 还不能完整替代旧行为。

建议调整：

1. CI 在当前命令中创建局部 handler，委托 CompileUiHandler.DEFAULT，只覆盖 confirmTooManyChanges 返回 CONTINUE。使用同一 handler 创建 status holder 并调用 compile，不新建公共 CI handler 类，不修改 DEFAULT 的默认策略。
2. 删除 CI 中两个 Int.MAX_VALUE 赋值。
3. JuggCompilerHelper 和 IncrementalCompilerHelper 的超阈值路径统一向 handler 获取选择；无 handler 的只读 checkFallback 仍返回现有 fallback 预测。
4. 将“关闭确认时直接回退”的偏好判断移入 JuggCompileUiHandler.confirmTooManyChanges：关闭确认或 RPC 模式返回 FALLBACK；普通 IDE Run 在开启确认时仍显示原对话框。Standalone/DEFAULT 原本就默认 FALLBACK，保持原行为。
5. CONTINUE 只放行“改动数量过多”这一条件；部署状态、依赖、构建文件、编译失败和取消仍走原流程。CI 的最终 DEX 合并流程保持不变。

这样，阈值属于全局配置，是否在本次 CI 超阈值后继续属于调用策略；不需要保存/恢复全局值，也不为阈值引入第二层覆盖状态。CompileUiHandler 的接口签名保持不变。

## 7. MCP 与 CLI

单一 MCP tool `settings`：`action=list|get|set|reset`、`key?`、`value?: string`、`confirm?: boolean`。list 不接受 key/value/confirm；get 需要 key；set 需要 key/value；reset 需要 key。set/reset 可接受 confirm。schema 检查基本类型，Editor 负责实际设置类型与确认。

加入 `noProjectDirTools`，IDEA/standalone 均暴露。GlobalMcpToolAction 增加 arguments，并由两个 invoker 传入已校验参数。version/list-projects 只适配签名，现有响应保持一致。

无项目 Runtime 选择复用发现、传输和启动锁；无需指定 --runtime。已选择的旧 Runtime 若不支持 settings，明确提示升级；不退回 Python 直写 JSON。JuggDaemon 允许空启动项目集合，保留现有项目启动流程。无项目启动日志位于 CLI cache，不在当前目录生成 build/jugg。

共享定义由 Runtime 持有，list 返回其中的 description，确认失败返回相应提示；不向 Python 复制字段清单、默认值或确认规则。更新 CLI 及 skill 版本/日期，确保安装用户收到命令与 help。

## 8. 已批准的文件范围

路径均相对仓库根目录。以下为已批准实现范围；具体落地和验证记录见第 11 节。

| 文件 | 责任 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt` | 完整设置定义集合、类型/默认值单一来源、阈值普通持久化、另外两个选项的进程覆盖、缓存刷新 |
| **新增** `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettingDefinition.kt` | IDE/CLI 共用的类型、描述和确认数据；以属性名为 key |
| **新增** `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettingsEditor.kt` | 共用解析、确认门禁、用户修改和响应；复用既有业务 owner |
| `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JsonRuntimeSettingsRepository.kt` | 严格读/写、原子多字段更新、保存后缓存更新所需结果 |
| `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServerChooser.kt` | 共享提示和用户选服规则、原子发布 URL/模式、避免后台选服覆盖新设置 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompatDeployHelper.kt`、`main/src/main/java/com/sickworm/intellij/jugg/deploy/SliceDeployHelper.kt` | 复用现有记录模型/校验，IDE 显式兼容编辑进入共享入口 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`、`idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | 从定义读取 label/description/确认条件，统一 Editor 调用和确认后重提 |
| **新增** `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggSettingsProjectEffects.kt` | 两类已确认项目效果的序号检查、现有历史/重装动作、应用状态持久化 |
| `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggPathManager.kt` | 项目 settings_applied.json 路径 |
| `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`、`idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | 在既有项目锁和上下文恢复边界应用设置效果；保持 UI 不阻塞 EDT |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneProjectServices.kt`、`cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneConfigurationRunner.kt` | 同样在基线使用前应用效果和刷新内存上下文 |
| **新增** `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/SettingsMcpToolAction.kt` | settings 参数/结果适配，调用共享 Editor |
| `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpErrorCode.kt` | CONFIRMATION_REQUIRED |
| `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | 注册与全局路由 |
| `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/GlobalMcpToolAction.kt`、`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpBaseInvoker.kt`、`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpToolInvoker.kt` | 全局参数传递与原有错误映射 |
| `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/VersionMcpToolAction.kt`、`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/ListProjectsMcpToolAction.kt` | 全局接口签名适配 |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneProjectRegistry.kt`、`cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/JuggDaemon.kt` | 暴露能力、无项目启动 |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/incremental/BuildIncrementalApkCommand.kt` | 删除两处阈值赋值，局部 CompileUiHandler 返回 CONTINUE，保留最终 mergeDex |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt`、`main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt` | 超阈值选择统一交给 handler；保留已有 Continue 状态和无 handler 的只读预测 |
| `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt` | 承接是否显示超阈值确认的用户偏好，保留 IDE/RPC 的现有行为 |
| **新增** `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_settings.py` | 命令解析、原文传递、--confirm 和输出 |
| `docs/skills/jugg-android-dev-loop/scripts/jugg.py`、`docs/skills/jugg-android-dev-loop/scripts/py/jugglib.py`、`docs/skills/jugg-android-dev-loop/scripts/py/help_registry.py` | 注册、自动全局路由、无项目启动、help |
| `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_version.py`、`docs/skills/jugg-android-dev-loop/SKILL.md` | CLI/skill 版本与使用指引 |

为保持职责清晰，定义类型、用户编辑流程、项目效果三个新增类型各自承担明确业务边界，不新增一对一接口。现有部署历史、服务器和记录类型继续复用。发现超出以上范围的实际需求时先更新方案。

## 9. 验证与文档

### 9.1 有价值的测试

- `main/src/test/java/com/sickworm/intellij/jugg/project/runtime/JsonRuntimeSettingsRepositoryTest.kt`、`main/src/test/java/com/sickworm/intellij/jugg/project/runtime/JuggSettingsTest.kt`：原子关联更新、失败不污染内存、未知字段保留、跨进程刷新及进程覆盖不落盘。修正该测试中使用已变为常量属性测试 reload 的失效断言。
- `main/src/test/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelperTest.kt`、`idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelperTest.kt`、`idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandlerTest.kt`：用户关闭确认时 CI handler 仍能继续、普通 Host 仍回退、CONTINUE 的后续轮次与取消语义。将原“全局关闭时永不调用 handler”的结构性断言改为真实回退/无对话框结果；确认偏好的主要 owner 移到 IDE handler 测试。
- `main/src/test/java/com/sickworm/intellij/jugg/compiler/TooManyChangesTest.kt` 及上列阈值测试在隔离的设置根目录执行，验证两种阈值实际生效，避免测试把临时阈值写入用户配置。
- **新增** `main/src/test/java/com/sickworm/intellij/jugg/ide/bean/JuggSettingsEditorTest.kt`：真实类型解析、结构化校验、所有确认条件、reset 和未确认无副作用。断言稳定业务结果，避免冻结普通文案措辞。
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelControllerTest.kt`、`idea/src/test/java/com/sickworm/intellij/jugg/server/JuggServerChooserTest.kt`：IDE 取消/确认与 CLI 的等价结果，Custom Server 联动及自动选服并发保护。
- **新增** `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/SettingsMcpToolActionTest.kt`；扩展 `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/McpInvokerValidationTest.kt`：参数透传、confirmation 错误结构、旧全局工具回归。
- **新增** `main/src/test/java/com/sickworm/intellij/jugg/project/runtime/JuggSettingsProjectEffectsTest.kt`：只在序号变化时执行必要动作、项目关闭/重启后的补应用、失败保留待处理要求。
- `cmd_line/src/test/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeTest.kt`：无项目 settings 可用且不注册项目；已有项目调用正常。
- **新增** `docs/skills/jugg-android-dev-loop/tests/test_cmd_settings.py`；扩展同目录 `test_jugglib.py` 和 `test_cmd.py`：camelCase key、原文 value、显式 confirm、JSON/退出码及自动发现；检查 list 每项 key/value/description，以及 get/set/reset 无 description，确认错误仍有提示。
- 因修改基线使用前的效果处理，使用 `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt` 或经记录的真实 IDEA/standalone Flow 矩阵验证：备份 classpath 改变后的完整构建、兼容记录改变后的重装、无变化正常增量路径、失败/取消后后续要求保留。不以单元测试或编译成功替代该证据。
- CI 采用实际两阶段构建验证：在隔离设置根目录保存低阈值，分别将 isConfirmFallbackWhenTooManyChanges 设为 true/false，使用各自独立的未消费基线运行含超阈值影响传播的 buildIncrementalApk；验证继续增量、最终 DEX 合并和 APK 输出，以及运行前后两个阈值的内存/持久化值不变。缺文件、非法输入或真实编译错误仍失败。此矩阵保护 CI 编排，不以仅测试局部 handler 返回枚举代替。

新增行为先获得失败证据再实现。不为每个普通字段、常量或纯转发添加测试；共享描述一致性通过共同数据来源、实际渲染结果和端到端确认流程验证，不复制一套字面文案快照。

运行定向 JVM/Python 测试、`tools/check_python_compat.py --target jugg_cli` 及必要编译。真实验证使用隔离的设置根目录和测试项目，不修改用户日常配置。测试中至少包含 Int/Long 溢出、nullable String、结构化字段错误、未确认/false/true、原提示返回、IDE/CLI 同一输入一致结果。

### 9.2 实现时同步文档

- 维护者：`docs/ai/05_utilities.md`、`docs/ai/08_mcp_design.md`、`docs/ai/08_mcp_tools_list.md`、`docs/ai/08_cli_tools_list.md`、`docs/ai/98_code_map.md`、`docs/ai/04_engineering_compat.md`、`docs/ai/04_engineering_ide.md`。说明共享定义、确认门禁、项目效果及全局自动路由。
- 编译策略：`docs/ai/02_compile_core.md`、`docs/ai/02_compile_custom_ui.md`；在现有 CI 文档段落说明 handler 定制，区分 CI buildIncrementalApk 与普通 jugg CLI。
- 安装后指引：`docs/skills/jugg-android-dev-loop/references/cli_manual.md`、`docs/skills/jugg-android-dev-loop/references/error_patterns.md`；按受影响程度同步现有 Flow 指引。
- Wiki：`docs/wiki/reference/cli-commands.md`、`docs/wiki/reference/configuration.md`、`docs/wiki/guide/cli.md`、`docs/wiki/guide/control-panel.md` 及对应 `docs/wiki/zh/` 镜像。配置页当前仍称 IDE properties 存储，实现时一并按代码事实纠正。
- 实现后按 feature-development 技能进行只读子代理评审。方案批准后在验证完成时执行实现评审。

## 10. 已核实证据与补充边界

- 上轮本地执行 `python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py help settings`，返回 unknown subcommand，退出码 1。
- 已核对 JuggSettings 的全部声明，Repository 的更新/异常/缓存行为，IDE 面板三类确认，JuggServerChooser、RunConfigurationTemplate、CompatDeployRecord、SliceDeployRecord 及现有消费者。
- 现状与目标：当前 CI BuildIncrementalApkCommand 覆盖两个 Int 阈值，本方案将其替换为 CompileUiHandler 策略并删除赋值；IDE TestGlobal 关闭 warm-up 的进程语义保留。不得将 CI 写作普通 CLI。
- 当前 GlobalMcpToolAction 不接收 arguments；CLI 发现和 daemon 启动按项目运行；这两点是全局 settings 必须补齐的入口。
- 共享存储使用实际全局根目录；若 home 不可写，某个 Runtime 可能使用临时目录。返回实际路径，提示用户统一根目录，不能以 Host 名称另建配置域。旧版 Runtime 不支持共享元数据/确认协议时提示升级。
- 常量、final 派生值、`isForceEnableBackupClasspath` 和 `isCanUseBackupClasspath` 属于只读能力/进程状态，list/get 可展示并标明来源；不改造成可持久化用户设置。私有缓存、远程命令历史、JSON backing 字段及效果序号不作为设置 key。defaultCompileSettings 是面向用户的对象入口。
- defaultCompileSettings 包含 SSH 密码等敏感字段；输出和错误不回显凭据。编辑按完整类型校验，不能把脱敏占位符当作密码保存。这里新增的是全局默认模板编辑，不是项目运行配置管理。
- `deployOnSave` 当前未找到实际消费点；允许保存该已有选项，描述明确其当前无独立生效逻辑，本任务不补做自动部署功能。平台不支持的功能在有效能力信息中说明。
- 效果类设置的变更在下一次项目使用前应用；执行中的任务仍沿用既有动态读取语义。用户需在任务空闲时修改以获得确定生效边界，本任务不引入所有设置的任务级快照隔离。

阅读依据：已读 `docs/ai/00_overview.md`、`docs/ai/99_index.md`、`docs/ai/98_code_map.md`、`docs/ai/05_utilities.md`、`docs/ai/08_cli_tools_list.md`、`docs/ai/08_mcp_design.md`、`docs/ai/06_testing.md`、`docs/ai/02_compile_core.md`、`docs/ai/02_compile_custom_ui.md`，以及 `docs/ai/04_engineering_compat.md` 的 standalone/锁章节、`docs/ai/04_engineering_ide.md` 的生命周期/Settings 章节、`docs/ai/03_deploy_core.md` 的恢复章节；同时读取 AGENTS.md、feature-development 技能和上列实现/测试。当前工作区未在 IDE 打开，上轮 index 返回 project_not_found，未用另一 checkout 代替取证。

设计阶段仅修订方案并检查路径/差异；实现已获批准，共享 Editor、全部类型和项目效果均纳入本次实施。

## 11. 实施记录

- 用户批准后在独立 worktree 的 codex/settings-cli 分支实施，起点 276e25695；原工作区存在其他任务改动，保持原分支和文件。

### 11.1 已落地行为

- 21 个可配置属性与只读能力由属性引用登记；共享类型、委托默认值、描述、确认与 Editor；新增 CLI list/get/set/reset 和无项目 MCP 路由。
- 保留跨进程字段合并，增加严格 JSON 输入、原子多字段提交、CAS 并发校验与缓存文件版本检查。确认失败不写入；reset 显式保存默认值。
- IDE 与 CLI 共用三类确认。Backup classpath / 显式兼容记录采用全局 revision + 项目 ack，既有项目锁内应用效果并复用 Host 内存恢复边界。
- CI 局部 handler 返回 CONTINUE；共享编译入口不再用 IDE 偏好截断 handler，偏好移至 IDEA handler。删除 CI 阈值赋值。
- 真实 HTTP 冒烟发现 Gson 会省略 nullable value，现已在 settings 响应及设置持久化中保留 null。McpLocalServer 同时去掉 settings 请求日志中的原始 value，避免模板凭据回显。这两处是维持已批准输出/敏感字段要求的协议边界修正。
- Compile on save 描述标明仅 IDEA 消费；deployOnSave 标明暂无消费者。未补做自动部署。
- RunConfigurationTemplate 接受部分对象并补齐同一类型默认值，严格校验给定字段、非空约束、端口及 syncMode；结果对密码/环境变量脱敏，拒绝保存脱敏占位符。
- 未修改无需改动的 SliceDeployHelper、JuggRunningTask、StandaloneConfigurationRunner：通过已有调用的恢复边界完成接入，未扩大接口。

### 11.2 验证证据

- 首次失败：CLI help settings 返回 unknown subcommand；新增 Kotlin editor 测试缺失 API、Python cmd_settings 导入失败，之后实现通过。
- JVM 定向回归已通过：main 77、IDEA 91、standalone 16 项；随后新增项目锁/Host status 消费验证通过，standalone 为 17 项。覆盖类型、Int/Long 溢出、JSON/模板、确认 false/true/取消、null、CAS、防覆盖、异进程文件替换、失败保留、编译后续轮次、项目历史/重新安装持久化、无项目 HTTP。
- Python 五个测试文件逐个运行，共 139 项通过；不合并 discover，避免旧包装测试对同名模块导入路径的污染。Python 3.7 AST 兼容检查通过；本机没有 python3.7，未执行该解释器运行验证。
- 独立临时 root 的真实 Python CLI → HTTP → 无项目 daemon 冒烟通过 10 项检查：空值字段、整数、确认错误/成功、非法值、reset、自定义 URL 原子模式、读取、零项目、当前目录没有 build/jugg。测试 daemon 已停止。
- 完整资源构建和目标测试使用 JBR 17 + ANDROID_HOME。首次 JDK 21 生成的旧 javac 产物触发旧 D8 NPE；以 JBR 17 重新生成 agent Java/AAR 后通过。旧 MaxPermSize 参数通过外部临时 Gradle init 排除，未改生产构建配置。
- 较宽 MCP 回归中发现既有 testRestartAppRejectSerialArgument 与当前支持 serial 的 schema 不一致；未改该无关断言。与本次有关的 validation/protocol/tool-success/version 回归均通过。
- Wiki 生产构建、双语/链接/HTML route 校验和 git diff --check 通过。CLI/Skill 版本分别升级为 1.0.25 / 1.0.39。
- CI 真实矩阵已通过：CmdLineTest.buildIncrementalApkAcceptsLargeChangesWithoutOverwritingSettings，先完整生成 baseline，再为 true/false 确认偏好各复制独立未消费基线。maxCompileSourceModules / maxCompileSourceFilePoints 均设为 1；两轮均完成真实编译、影响传播、merged_dex 和 APK 输出，持久化文件及有效阈值不变。无效 Java / 缺失文件两轮失败且没有 APK、未修改 settings。测试恢复源码和全局 root，并删除临时基线。
- 项目效果真实 Flow 已通过：`SettingsCompileFlowTest` 使用真实 standalone Host、Gradle 和编译器完成“完整基线 → 普通增量 → Backup 修改 → Host 清除内存基线 → 完整构建失败 → 再次完整构建成功 → 增量 DEX”。`SettingsDeployFlowTest` 连接真实持久化历史和已有虚拟设备部署编排，从匹配设备状态开始，经 Editor / 项目锁消费生成重装要求；取消与安装脚本失败均保留要求且没有安装，成功运行实际执行安装并清除要求。外部设备边界由虚拟设备替代。两项均通过且未跳过。
- feature-development 要求的只读子代理实现审查和修正复核已完成：自动选服缺口已修复，项目效果 Flow 证据已补齐，没有剩余必须修复的问题。
- 审查修正后的最终定向回归通过：main 65、IDEA 100、standalone 17 项，失败/错误/跳过均为 0。真实 CLI → 无项目 daemon 的 10 项冒烟再次通过。demo 源码已恢复，未产生测试用源码改动。

补充依据：实际修改维护者文档时读取 docs/ai/97_maintenance_manual.md；Wiki 同步读取 wiki-writer 技能、docs/ai/10_wiki_authoring.md、10_wiki_architecture.md，以及 reference/index.md、guide/index.md 和受影响英文章节；中文以英文改动为基线同步。

### 11.3 实现审查修正

- 只读审查指出恢复自动选服不能仅清 URL 等待后续请求。已在 JuggServerChooser 中复用候选选择，锁外生成实际 URL/过期时间并由 Editor 原子提交；有候选立即切换，无候选保留旧 URL 并标记自动重选。IDE 使用当前 chooser 的业务规则，其他入口使用嵌入规则；同一 Editor 统一校验、确认、提交和返回实际值。
- 给现有模板 String adapter 增加 nullSafe，确保已按实际 nullable 类型接受的字段能被运行时 getter 读取，不静默回到默认模板。
- 补齐有候选/无候选/空 URL 的自动选服测试及 nullable 模板读取断言；同步修正 MCP capability 文档枚举。
- 全局日志器派生子日志器继续使用全局 logger，避免无项目 Editor 初始化被项目注册校验拒绝；无项目定向测试验证通过。
- 原有 reload 测试使用已变为常量的兼容模式属性而失败，改以实际持久化的 compileOnSave 验证跨 Runtime 刷新；阈值相关测试改在隔离设置根目录运行，避免临时阈值落入用户配置。

### 11.4 阅读依据

- 仓库 AGENTS.md；feature-development / wiki-writer 的 SKILL.md。
- docs/ai/：00_overview.md、99_index.md、98_code_map.md、05_utilities.md、08_mcp_design.md、08_mcp_tools_list.md、08_cli_tools_list.md、06_testing.md、97_maintenance_manual.md、02_compile_core.md、02_compile_custom_ui.md、04_engineering_compat.md、04_engineering_ide.md、03_deploy_core.md、10_wiki_authoring.md、10_wiki_architecture.md。
- docs/wiki/：reference/index.md、guide/index.md；reference/cli-commands.md、reference/configuration.md、reference/mcp-tools.md、guide/cli.md、guide/control-panel.md 及这五页的 zh 镜像。
- 代码事实以 JuggSettings / JuggSettingsEditor、JuggServerChooser、全局 MCP 分发、CLI Runtime 发现、CompileUiHandler、项目锁及 Host 基线恢复路径为准；验证证据见 11.2。
