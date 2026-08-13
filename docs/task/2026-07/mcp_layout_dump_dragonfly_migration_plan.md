# MCP UI 数据源统一迁移到 Dragonfly 方案

> 状态：统一数据源版本已实施；Dragonfly 已更新为自带私有运行时的 DEX JAR
> 日期：2026-07-22  
> 目标：移除 Jugg 自有的 layout dump 数据采集实现，改用 App 端 Dragonfly；保持 MCP/CLI 对外参数、返回结构和 HTML artifact 格式不变，并增加 Compose 节点抓取。

## 1. 结论

推荐采用以下边界：

```text
MCP / CLI（保持不变）
  -> LayoutDumpHelper（保持公开结果不变）
  -> ViewHierarchyClient + 现有 LocalSocket（保持不变）
  -> ViewHierarchyServer
  -> Dragonfly App 端节点树
  -> DragonflyHierarchySource.Snapshot
  -> dump / selector / tap / inspect / layout verify
```

- 不使用 `dragonfly_flight`，不新增电脑端依赖。
- 不直接透传 Dragonfly JSON。Dragonfly 原始结构是以节点名为动态 key 的字符串属性树，与 Jugg 当前 `windows[]/root/children[]` 格式不兼容。
- Dragonfly 是 ViewHierarchy App 端唯一节点数据源。`layout_dump`、selector、元素点击、`view-inspect` 和内部 `layout-verify` 每次请求都从新抓取的一份 snapshot 工作；仅在 Dragonfly 窗口枚举失败时复用旧根窗口枚举，根节点仍交给 Dragonfly 提取。
- `view-locate` 当前复用 `LayoutDumpHelper.dumpInternal()`，只要适配后的 Compose 节点仍使用标准 `children[]` 节点格式，即可自然支持按 `text` 查找 Compose 节点。
- Android 节点仍保留 Dragonfly 提供的原始 `View` 引用；Compose 节点 tap 暂以 bounds 中心向所属 root View 派发 MotionEvent，inspect 暂对 Dragonfly 节点对象执行允许的 getter。

当前接入产物：

- `dragonfly_0.jar`，SHA-256 `320aa0bf78caef7fb586a96d511cf6f01f8b2a494e796f670b72b9cc9f508abd`
- `implementation_0.jar`，SHA-256 `3d7433d930ac5eac9486d76e0460490a4b10974fb8f5964a8f279e69cbb4dc9d`
- 两个 DEX JAR 先离线转为 class JAR，再将 Dragonfly API 与内置 Kotlin、coroutines、Guava、dexlib2 依赖统一重命名到 `com.sickworm.intellij.jugg.internal.dragonfly.**`。预处理 JAR 同时进入 bootstrap 加载的 `jugg-instruments.jar` 与 Gradle 注入 App 的 `jugg-runtime.jar`，电脑端 main/idea classpath 不引入 Dragonfly。
- Dragonfly 自带私有运行时，纯 Java 工程不再依赖宿主 Kotlin；Compose 不兼容仍由 Dragonfly 内部局部收口。
- 指定 `rootLayout` 时无视 top-window 默认值并自动跨窗口查找，恢复旧行为。

## 2. 当前已知限制

### 2.1 Dragonfly 使用私有运行时

`implementation_0.jar` 内置 Kotlin stdlib、coroutines、Guava 与 dexlib2。Jugg 在离线预处理时同步重命名这些类和 `dragonfly_0.jar` 中的引用，避免与宿主 App 同名依赖冲突；Java-only App 可直接使用。Compose 兼容失败仍由 Dragonfly 局部收口。

### 2.2 Dragonfly 字段不足以原样提供 Jugg 当前语义

Jugg 当前标准节点字段包括：

```text
className, id, text, contentDesc, tag, bounds,
visibility, alpha, clickable, enabled, padding,
textColor, message, children
```

Dragonfly Android View 节点可提供：

```text
name, id, visibility, bounds, width, height, padding,
margin, alpha, background, isFocused, isFocusable,
hashCode, text, fontSize, textColor
```

Dragonfly Compose 节点可提供：

```text
name, bounds, width, height, sourceFile, lineNumber,
hashCode, text, fontSize, textColor
```

Dragonfly Android 节点本身也没有输出 `contentDesc/tag/clickable/enabled`，但其 `ViewNode.getView()` 可拿到原始 Android `View`，适配层可以补齐这些字段，不需要恢复旧的视图树遍历。以下字段对 Compose 节点仍无法等价获得：

- `contentDesc`
- `tag`
- `clickable`
- `enabled`
- `padding`
- 稳定 `resourceId`

可以保持 JSON schema 不变，但缺失值只能省略或使用当前默认语义，不能承诺字段值与旧实现完全等价。

### 2.3 `Dragonfly.init()` 不应在本方案调用

说明文档明确该方法用于初始化 `dragonfly_flight` 通信。当前方案不使用电脑端，因此不注册 Dragonfly BroadcastReceiver，避免额外引入 coroutines 通信链路。

Compose 抓取需要在首个 layout dump 时调用 `Dragonfly.enableComposeExtract()`，并等待下一帧后再提取，避免在 `Application.onCreate()` 尚无 Compose View 时过早完成一次性初始化。

## 3. 需要补齐或确认的输入

### 3.1 全量 Jugg 用户都要支持时

需要 Dragonfly 提供方补充一个适合嵌入通用插件运行时的版本：

1. 明确支持 `minSdk 21`。
2. 提供 Maven/POM 或完整依赖清单及版本兼容矩阵。
3. 移除对 Material3 的硬依赖；可选能力应使用反射或独立模块。
4. 明确兼容的 Kotlin stdlib 最低版本，不能默认目标 App 已使用 Kotlin 2.0。
5. 明确兼容的 Compose runtime/ui/tooling 版本范围。
6. 提供 ProGuard/R8 consumer rules。
7. 提供版本号、许可证和二进制来源；最终 JAR 需进入仓库受版本控制，不能依赖 Downloads 路径。
8. 最好提供仅包含 App 内提取 API、不包含 flight/receiver/coroutines 通信代码的轻量 JAR。

### 3.2 只要求支持指定业务 App 时

需要确认该 App 的目标 variant 同时具备：

- Kotlin stdlib 与 Dragonfly 二进制兼容；
- Compose runtime/ui；
- Compose ui-tooling-data；
- Material3；
- debug/release 两种需要支持的 variant 均不会被 R8 删除相关类。

还需要提供一个可运行的 Compose 页面作为验收页，至少包含：

- 普通 Text；
- 嵌套 Layout；
- 重复文本；
- 弹窗或第二 Window；
- 一个传统 Android View 与 Compose 混合页面。

## 4. 数据兼容约定

### 4.1 MCP/CLI 公开契约保持不变

以下内容不变：

- tool name：`layout-dump`
- 参数：`projectDir/rootLayout/includeGone/allWindows`
- `McpToolResult.status/message/data/artifacts/errorCode`
- `data.file`
- `data.contentBytes`
- artifact type：`html`
- CLI 命令和参数
- 输出目录：`build/jugg/mcp_fetch/layout-dump/`

### 4.2 内部标准 JSON 保持不变

继续输出：

```json
{
  "windows": [
    {
      "windowType": "activity",
      "title": "MainActivity",
      "root": {
        "className": "FrameLayout",
        "bounds": [0, 0, 1080, 2400],
        "children": []
      }
    }
  ],
  "truncated": false,
  "deviceInfo": {
    "density": 3.0,
    "scaledDensity": 3.0
  }
}
```

Dragonfly 节点统一适配为标准 `children[]`，不直接暴露 Dragonfly 的动态节点名 JSON，也不新增公开字段。

### 4.3 字段映射

| Jugg 字段 | Dragonfly 来源 | 规则 |
|-----------|----------------|------|
| `className` | `HierarchyNode.name` | 继续使用简单类名；Compose 使用 composable name |
| `id` | Android `id` | 无 id 时基于 window/child path、节点名、sourceFile、lineNumber 生成确定性 `_vir_id_<hash>`；HTML 继续隐藏虚拟 id |
| `text` | `text` | Android TextView/Compose Text 均映射 |
| `bounds` | `bounds` | 解析 `l,t,r,b` 字符串为 4 个整数，后续仍由 IDE 侧统一 px -> dp |
| `visibility` | Android `visibility` | Compose 缺失时按默认 `visible` |
| `alpha` | Android `alpha` | Compose 缺失时按默认 `1.0` |
| `enabled` | Android `View.isEnabled()` | Compose 缺失时按当前默认 `true` |
| `clickable` | Android `View.isClickable()` | Compose 缺失时按当前默认 `false`，不伪造可点击语义 |
| `padding` | Android `padding` | Compose 缺失时省略 |
| `textColor` | `textColor` | 保持 `#AARRGGBB` 字符串 |
| `contentDesc/tag` | Android 原始 `View` | Compose 缺失时省略 |
| `message` | 无 | 缺失时省略 |
| `children` | `HierarchyNode.children` | 递归适配，统一限制深度和节点数 |

### 4.4 参数语义

- `allWindows=false`：调用 `extractTopWindow()`。
- `allWindows=true`：Dragonfly 当前公开 API 没有 window count/list，按 `-1/-2/...` 从顶层向底层逐个探测，直到返回 null；实现时必须设置最大窗口数，避免无界循环。
- `rootLayout`：先生成标准树，再按标准 `id` 查找子树；Compose 虚拟 id 在 Dragonfly 遍历顺序和 UI 结构不变时跨请求一致，但不是业务稳定标识。
- `includeGone=false`：在适配树阶段过滤 `visibility=gone` 节点。
- `MAX_DEPTH=60`、`MAX_NODE_COUNT=5000` 和 `truncated` 语义继续保留。

## 5. 实施步骤

### 阶段 A：依赖准入验证

1. 将确认后的 JAR 放入 `jvmti_agent/libs/dragonfly/`，文件名包含真实版本号。
2. 在构建脚本中校验 JAR 存在并记录 SHA-256。
3. 验证非 Compose demo、当前旧 Compose demo、目标 Compose App 的 debug 构建。
4. 验证最终 APK 中 Dragonfly 类只出现一次，不产生 Kotlin/Compose duplicate class。
5. 验证 release/R8 场景或明确本功能仅支持 debuggable variant。

未通过本阶段时，不进入生产代码替换。

### 阶段 B：先写失败测试（TDD）

执行清单：

| 测试文件 | 层级 | 覆盖内容 |
|----------|------|----------|
| `jvmti_agent/src/test/java/com/sickworm/intellij/jugg/viewhierarchy/DragonflyHierarchySourceTest.java` | L1 | 同一 snapshot 驱动 dump/selector、确定性 ID、过滤 GONE、子树与截断；DEX JAR 转换出的真实 Android 节点类不在 JVM 加载，交给 L3 覆盖 |
| `jvmti_agent/src/test/java/com/sickworm/intellij/jugg/viewhierarchy/ViewTapperTest.java` | L1 | Compose 坐标 tap、long press 异步抬起且不阻塞主线程 |
| `jvmti_agent/src/test/java/com/sickworm/intellij/jugg/viewhierarchy/LayoutVerifierTest.java` | L1 | Android 属性验证回归、Compose 缺失属性显式 unavailable |
| `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpMcpToolActionTest.kt` | L2 | MCP `data/artifacts/message` 前后不变；Compose 标准 JSON 仍生成 HTML |
| `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutHtmlConverterTest.kt` | L1 | Compose 节点在现有 HTML 格式中可见，虚拟 id 规则不变 |
| `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/ViewLocateMcpToolActionTest.kt` | L2 | Compose text 可通过现有 `view-locate` 返回 bounds |
| `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ReadProjectInfoGradle7CompatTest.kt` | L2 | runtime JAR 注入后构建成功且包含 Dragonfly 类 |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt#testLayoutDumpWithBundledDragonflyRuntime` | L3 | 真设备完整安装、增量下发并重启 startup agent 后实际 dump，验证私有 Dragonfly runtime 可加载并返回窗口根节点 |

所有生产代码修改必须在上述失败测试和测试路径清单确认后开始。

### 阶段 C：App 端适配

1. 新增 `DragonflyHierarchySource`，负责：
   - 调用 Dragonfly 提取 API；
   - 将 `HierarchyNode` 递归转成现有 `ViewNode`；
   - 对 Android 节点通过 Dragonfly `ViewNode.getView()` 补齐 `contentDesc/tag/clickable/enabled` 和窗口元数据；
   - 执行 GONE、rootLayout、depth/count 规则；
   - 生成现有 `windows/truncated/deviceInfo` JSON。
2. `ViewHierarchyServer` 的 dump、selector、tap、inspect、verify 都按请求抓取 Dragonfly snapshot。
3. 首次 Compose dump 在 UI 主线程调用 `enableComposeExtract()`，下一帧提取；失败时返回明确错误，不让 App crash。
4. 删除 `ViewTreeDumper`、旧 adapter 和 Compose 占位树；所有 App 端 UI action 不再维护第二套遍历实现。
5. 不新增新的 MCP 参数，不修改协议 envelope；如 App 端协议字段未变化，可不升级协议版本。

### 阶段 D：构建产物接入

1. `preprocess.sh` 使用固定版本和 SHA-256 的 dex2jar、Jar Jar Abrams，将源 DEX JAR 转为 class JAR，并把 Dragonfly API 与内置运行依赖离线重命名到 `com.sickworm.intellij.jugg.internal.dragonfly.**`；正式 Gradle 流程只消费提交到仓库的预处理 JAR。
2. `buildAgentBundle.gradle` 将预处理 Dragonfly JAR 同时交给 D8 生成 `jugg-instruments.jar`，并合并进现有 `jugg-runtime.jar`，避免改变 `GradleApplicationInjector` 的单 runtime JAR 接口。
3. runtime JAR 合并时排除签名文件和重复 `META-INF` 条目。
4. Dragonfly 不进入电脑端 main/idea classpath。
5. 不调用 `Dragonfly.init()`，不启用 flight receiver。
6. 构建时校验 instruments/runtime 产物包含私有 Dragonfly 与 Kotlin runtime 入口，runtime JAR 不得包含原包 class entry。

### 阶段 E：文档同步

需要同步：

- `docs/ai_knowledge/98_code_map.md`
- `docs/ai_knowledge/08_mcp_layout_verify_design.md`
- `docs/ai_knowledge/08_mcp_tools_list.md`
- `docs/ai_knowledge/08_mcp_design.md`
- `docs/ai_knowledge/08_cli_tools_list.md`（参数不变，只更新实现说明）
- `docs/skills/jugg-android-dev-loop/` 中关于 layout dump 数据源、Compose 边界和错误处理的描述

## 6. 验收标准

### 6.1 格式回归

- 相同传统 View 页面，MCP result key、artifact 类型和 HTML 结构不变。
- 内部 JSON 顶层和标准节点 key 不新增、不改名、不改类型。
- bounds/padding 仍以 IDE 侧转换后的 dp 输出给下游。
- `rootLayout/includeGone/allWindows` 参数继续生效。
- `view-locate` 对传统 View 的行为不回退。

### 6.2 Compose 能力

- Compose Text 出现在 HTML 中。
- Compose 节点包含正确 dp bounds。
- 混合 View/Compose 页面层级完整。
- 重复文本在 `view-locate` 中返回正确 `matchCount/matches[]`。
- 无 resource id 的 Compose 节点不会伪造稳定业务 id。

### 6.3 稳定性

- 非 Compose App 不因 Dragonfly 类加载而 crash。
- Dragonfly/Compose 不兼容时返回可诊断错误，不影响 App 正常运行。
- Dragonfly 原始提取成功后，Jugg snapshot 最多保留 5000 节点和 60 层；该边界同时约束 dump、selector、tap、inspect、verify。原始提取先失败时不能承诺 `truncated:true`。
- App 侧主线程提取受现有 5 秒超时保护。

### 6.4 历史设备侧验证

- Kotlin 1.7.21 / AGP 7.2.2 demo 注入新 runtime 后 `assembleDebug` 成功。
- 传统 View MCP 测试页：`layout_dump` 成功；`find_and_tap` 点击成功；`eval_view` 实时读到点击后的文本；`verify` 文本断言 PASS。
- 旧版 Compose AAR 在 Compose 1.2.x 页暴露 tooling API 缺失；当前 DEX JAR 已替换该版本，仍需重新执行目标 Compose App 的 L3 验证。
- 实体机因同包名签名不一致未卸载原 App；上述运行验证在 Pixel 6 API 35 模拟器完成。

## 7. 待完善功能点

1. Dragonfly 仍需给出 Compose runtime/tooling 兼容矩阵，并在目标 Compose App 上完成覆盖验证。
2. Dragonfly Compose 节点尚无语义 action。Jugg 的中心点 MotionEvent fallback 不等价于 Semantics `OnClick` / `OnLongClick`，也无法识别 disabled/stale 节点。
3. Compose 节点缺少 `contentDescription/clickable/enabled/longClickable/padding/alpha/backgroundColor` 等属性；selector 候选提示与 layout verify 只能显式返回 unavailable。
4. Compose inspect 当前反射读取 Dragonfly 节点对象；Android View 专属 getter 会逐表达式返回错误，仍需要稳定的 Compose property/read API。
5. 确定性虚拟 ID 依赖 Dragonfly 的 window/children 遍历顺序；列表重排、条件节点插入或窗口顺序变化会产生新 ID。
6. 需要目标 Compose App 的 L3 页面验证 bounds 是否为绝对屏幕坐标，以及 Dialog/Popup、嵌套 Compose、重复文本、重组后的 action 行为。
7. 当前 Compose clickable 信息缺失，因此 not-found 时的 clickable candidates 不能完整覆盖 Compose 节点。

## 8. 已读文档与依据

- `docs/ai_knowledge/00_overview.md`
- `docs/ai_knowledge/99_index.md`
- `docs/ai_knowledge/98_code_map.md`
- `docs/ai_knowledge/08_mcp_layout_verify_design.md`
- `docs/ai_knowledge/08_mcp_tools_list.md` §`layout-dump`
- `docs/ai_knowledge/08_mcp_design.md` §7、§9、§10
- `docs/ai_knowledge/08_cli_tools_list.md` §`layout-dump`
- `docs/ai_knowledge/06_testing.md` §2、§5、§7、§8、§11
- `/Users/wormchen/Downloads/Dragonfly Preview (1).docx`
- `/Users/wormchen/Downloads/dragonfly_v0_0_0(2).jar` 公共 API、字节码与反编译结果
- `LayoutDumpHelper.kt`、`LayoutDumpMcpToolAction.kt`、`LayoutHtmlConverter.kt`
- `UiFindMcpToolAction.kt`、`ViewHierarchyClient.kt`、`ViewHierarchyProtocol.kt`
- `ViewHierarchyServer.java`、`DragonflyHierarchySource.java`、`ElementFinder.java`、`MatchedElement.java`、`ViewTapper.java`、`LayoutVerifier.java`、`ViewNode.java`
- `BootstrapApplication.java`、`ViewHierarchyServerLoader.java`
- `jvmti_agent/build.gradle`、`jvmti_agent/buildAgentBundle.gradle`
- `GradleApplicationInjector.kt`、`GradleScriptWriter.kt`
