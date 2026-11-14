# Compose 页面采集缺失修复方案

创建日期：2026-10-14。状态：代码已实施；本地回归与产物检查通过，原设备真机验收待完成。

## 1. 目标与现场依据

JOJO MainActivity 可见 `JOJO` 和“调试悬浮窗”，但历史 `layout-dump` 只返回 6 个 Android View，树停在 `AndroidComposeView`，`view-locate` 按悬浮窗文本查找失败。修复目标是恢复 Compose 内容采集，并让局部采集失败可识别。

已确认的证据：

- 原始文件：JOJO 工程 `build/jugg/mcp_fetch/layout-dump/layout_1791970728265.json` 与同名 HTML；JSON 已缺失 Compose 内容，HTML 不是采集失败的起点。
- 历史日志：`~/.jugg/log/android_35d1138e/compile_2026-10-14_17-10-40.0.log`，17:38:48 dump 返回 6 nodes / 0 clickable / not truncated，17:39:02 文本定位失败；同一任务部署记录使用 Agent `1.0.81`。
- 设备当前 JOJO 进程映射了 `/data/local/tmp/jugg/1.0.81/jugg-instruments.jar`。拉取文件 SHA-256：`756a6df7eb79ae7800532f549ccd8fe8d79a7131906f821020b6bcab0a2a7502`。
- 该设备 DEX 与当前源码均在 `containsComposeView()` 中使用单参数 `Class.forName(AbstractComposeView)`，失败后静默返回 false；Dragonfly 的 `ComposeAdapterKt` 经 `_ReflectionKt.toClass()` 使用相同加载方式，失败的 null 结果被 lazy 缓存。
- `instrumenter.cc` 将 instruments DEX 加入 bootstrap 搜索路径；Dragonfly Compose extractor 还直接引用宿主 `androidx.compose.*` 类型，因此只替换外层反射调用不足以修复整个链接边界。

高置信度根因是 bootstrap 采集器无法访问 App ClassLoader 中的 Compose。没有当时加载器实例和异常栈，实施前仍需记录一次冷启动中 server、Dragonfly、ComposeView 的实际加载器，排除运行路径与推断不一致。当前进程证据不等同于历史进程快照。

## 2. 推荐设计：采集链由 App ClassLoader 加载

保留现有 ViewHierarchy LocalSocket、Dragonfly 数据源、snapshot、selector、tap 和 inspect 调用链。把采集链从 bootstrap 产物移出，使其自然访问宿主 Compose；不新增通用插件加载器、第二套节点提取器或 uiautomator 回退。

### 2.1 产物与启动边界

| 部分 | 调整后归属 |
|------|------------|
| JVMTI instrumentation、hotfix、Direct runtime | 继续保留在 bootstrap instruments DEX |
| `com.sickworm.intellij.jugg.viewhierarchy.**` | 仅进入注入 APK 的 `jugg-runtime.jar` |
| `com.sickworm.intellij.jugg.internal.dragonfly.**`，含私有 Kotlin/协程 | 仅进入 `jugg-runtime.jar`，保持既有私有包名 |
| `BootstrapApplication` 启动采集服务 | 用宿主 Context 的 ClassLoader 反射调用 `ViewHierarchyServerLoader.init(Context)` |

具体修改：

1. `buildAgentBundle.gradle`：在 D8 输入边界过滤 ViewHierarchy 包，不再把 Dragonfly 两个 JAR 合并到 instruments；runtime JAR 保留完整采集链。校验最终 DEX 的 class definitions，而非只搜索描述符字符串；bootstrap 产物不得定义采集链类。
2. `BootstrapApplication.java`：移除对 `ViewHierarchyServerLoader` 的直接类型引用，以 `context.getClassLoader().loadClass(...)` 启动。跨加载器只传 Android `Context`，不传 Dragonfly 节点或私有 Kotlin 类型。不使用线程 context ClassLoader 代替显式加载。
3. 启动失败记录入口类、实际加载器及原始异常，局部关闭 UI 采集服务，保留 App 启动和部署；不能伪造 server 已启动。
4. 检查反射入口的 R8 保留规则，最小保留 `ViewHierarchyServerLoader.init(Context)` 及 Dragonfly 已确认按字符串加载的入口。涉及 `GradleApplicationInjector` 时，同步生成的 Gradle init script；不扩大为保留全部宿主代码。
5. 验证普通启动、compat deploy、原始 Application 自定义 ClassLoader 和 Direct runtime 仍符合上述边界。普通 View 与 Compose 使用同一服务实例和 snapshot。

仅移动 Dragonfly adapter 会留下 bootstrap 中的直接类型引用和节点类型边界；只把相同类再放进 App classpath 也会被 parent-first 委派命中 bootstrap。必须同时消除 bootstrap 中的采集链定义及其直接调用入口。

### 2.2 Compose 检测与首次采集

- 使用已取得的真实 View 及其加载器判断 Compose 宿主；至少识别 AbstractComposeView 继承链。无 Compose 的窗口沿用现有普通 View 路径。
- 在第一次 Dragonfly 提取之前识别当前根树中的 Compose 宿主，再启用提取，避免过早触发 adapter lazy 探测；加载器正确时才进入 Dragonfly 初始化。
- 首次启用后给 Compose 一次实际布局/绘制机会，再生成同一请求的最终 snapshot。现有代码在一个主线程调用中立即二次提取，不能保证 inspector/slot table 已就绪。
- 等待放在现有服务后台请求与主线程回调之间；主线程不得 sleep、await 或等待自身下一帧。复用现有请求超时，取消过期回调，不引入无限轮询。
- 每个请求最多进行一次“启用后重新采集”；再次采集只在初始化改变了条件后执行。后续请求直接抓取实时 snapshot，不重复 hot reload。
- 启用失败保留真实原因；不把正常空 Compose 容器等同于确定失败，也不因一个宿主失败丢弃其他窗口/宿主的有效节点。

## 3. 失败诊断与对外结果

优先复用现有节点 `message`，把 Dragonfly 的 error/warning 节点原因带入 snapshot；必要时仅增加可选的本次采集诊断字段。区分“明确提取失败”和“发现宿主但尚未获得内容”，后者不能直接断言 API 不兼容。

| 场景 | 预期结果 |
|------|----------|
| 普通 View 或 Compose 采集成功 | 原有成功结果、树结构与 selector 语义保持一致 |
| 部分 Compose 内容不可用，普通 View 有效 | 保留 JSON/HTML artifact 与有效节点；摘要明确告知内容不完整并附原因 |
| 查找命中有效节点 | 继续返回结果；存在采集诊断时随结果携带，不阻断可用能力 |
| 没有命中，且相关采集范围不完整 | 返回明确的 UI 层级不完整错误，不能仅用 `ELEMENT_NOT_FOUND` 断言不存在 |
| 预算截断 | 继续使用现有 `truncated`，与 Compose 提取失败分别报告 |
| 服务无法启动 | 明确报 server 不可用，记录根因及恢复步骤 |

`LayoutHtmlConverter` 保留带诊断的宿主，输出转义后的诊断信息；仅调整 HTML 不能作为采集修复。`LayoutDumpHelper` 在摘要中展示内容不完整的原因，避免无说明的 OK 被理解为完整采集。

诊断在 snapshot 处统一生成，dump、find、inspect、元素 tap 消费同一事实。错误类型优先复用语义匹配的既有错误码；若没有匹配项，只增加一个明确的 UI 层级不完整错误码，并同步 MCP/CLI 错误说明。不引入新的通用 PARTIAL 状态或改变 selector 参数。

## 4. 版本与升级

- 修改 Java runtime 和 bundle 后必须递增实际实施分支上的 `agentVersion`。不能固定写成 1.0.82：设备已有该目录，需核对工作分支及发布版本后选择未占用的新版本。
- 首次验证使用新插件、新 agent 和重新 Gradle 构建安装的 APK，确保 App 内 `jugg-runtime.jar` 更新；只重启旧 APK 不保证获得修复。
- 升级前后分别保存 APK 指纹、agent 路径/哈希及加载器信息。旧 APK 仍可能执行旧 bootstrap 入口或旧采集器，不能把它作为新加载边界的验证结果。
- 老 APK 缺少新入口或启动失败时，仅报告 UI 采集不可用并指导完整构建安装；不自动清数据、重装、反复重试或影响正常部署。
- 保持 Dragonfly 私有 Kotlin/协程运行时，纯 Java App 不应新增宿主 Kotlin 依赖。加载器修复后若暴露真实 Compose/tooling API 或私有化后 ABI 不匹配，先保存具体 linkage 错误，再针对该边界最小适配；不预先升级 BOM、Kotlin 或引入多版本框架。

## 5. 验证与完成条件

### 5.1 测试价值与 owner

- 核心失败边界是 Android 实际类加载和 Compose runtime，现有 mock HierarchyNode 的 JVM 测试不能证明修复；用真机端到端证据验收，不制造 mock-only seam 或反射私有状态测试。
- 构建产物边界是稳定契约：检查实际 class definitions 与冷启动加载器，验证采集链仅由宿主侧定义；保留必要的静态产物校验。
- 复用 `DragonflyHierarchySourceTest`（L1）保护 JSON/selector、过滤和截断契约；若新增诊断导致可观察序列化行为变化，先增加失败断言，再改实现。
- 在 `LayoutHtmlConverterTest`、`LayoutDumpMcpToolActionTest` 与既有查询 owner 中，按实际变更补充“有效内容保留、诊断不丢失、未命中不误报不存在”的行为断言。不测试日志精确措辞或私有 helper 调用次数。
- 仅运行带过滤的定向测试；本方案文档阶段只做文本、路径和 diff 检查。

### 5.2 真机验收矩阵

| 场景 | 必须取得的证据 |
|------|----------------|
| 原 SM-N9750 / API 31 / JOJO MainActivity | 同页相邻时刻截图、完整响应、JSON/HTML；能定位 JOJO、调试悬浮窗，bounds 对应可见区域 |
| 首次采集、重复采集、切换后返回、新建 Compose 宿主 | 首次即可返回已稳定显示的内容；后续无持续缺失，不反复初始化 |
| 动态时钟 | 采到当前文本；不用固定时间字符串断言 |
| 悬浮入口 | 位置正确，元素点击结果正确；不要求标题 Text 自身 clickable=true。既有 Compose MotionEvent 点击边界若暴露单独缺陷，单独记录，不扩大本修复 |
| 混合 View/Compose、多个窗口 | 单个 Compose 失败仍保留有效 View 与其他宿主；allWindows/rootLayout 语义不变 |
| 纯 Java 普通 View 页面 | 不依赖宿主 Kotlin，dump、定位、inspect、点击保持可用 |
| Compose 依赖缺失或已确认不兼容的 fixture | 无 App crash；保留有效内容、真实异常与不完整诊断；无假 ELEMENT_NOT_FOUND |
| 无 Compose 与正常空 Compose 容器 | 不误报兼容故障，不触发无界重试 |
| compat 启动与 minified APK | 新入口不被 R8 删除；实际加载器正确，普通采集可用 |
| 升级前旧 APK与升级后新 APK | 恢复提示准确；新 agent/新 APK 指纹可核对，不混用旧 runtime 验收 |

执行 agent/runtime 构建、必要的插件编译及上述定向测试；构建通过不能替代真机验收。若没有目标环境，明确列出未验证项，不宣称完成修复。

## 6. 文档同步与交付

实施时更新 `03_runtime_jvmti.md` 的产物加载归属、`08_mcp_layout_verify_design.md` 的加载边界与诊断、`08_mcp_tools_list.md` 的结果说明和 `98_code_map.md` 中相应描述。需要修改 CLI/skill 错误说明时按现行版本规则递增并验证安装产物。

Wiki 已确认受影响页面：`zh/capabilities/tools/ui-automation.md` 目前只说明导出/查找成功结果，需补充 Compose 内容不完整的判断及升级恢复步骤；`zh/reference/mcp-tools.md` 需同步实际落地的诊断与错误语义。实施时仅补充这些用户信息并按 Wiki 规则同步英文，不写内部 ClassLoader 细节。当前只是方案，不提前修改现行能力文档。

交付需包含定向测试结果、产物边界检查、同页截图/dump/定位结果、新 agent 与 APK 指纹、失败降级验证及未完成项。所有实现均围绕本问题提交，不修改 JOJO 业务页面来迁就采集器。


## 7. 实施记录（2026-10-14）

### 已落地行为

- Agent 版本更新为 `1.0.83`，避开现场已有的 `1.0.82` 目录。ViewHierarchy 和私有 Dragonfly 只随 APK runtime 加载；bootstrap 入口显式使用 Context ClassLoader 反射启动，失败保留异常栈。
- DEX class definitions 校验禁止采集链进入 instruments；runtime JAR 校验服务入口、Compose adapter 和私有 Kotlin。反射服务入口有最小 keep；adapter 构造器仅在宿主含 AbstractComposeView 时保留，并保留名称匹配所需的两个 Compose 宿主类名，生成 init script 已由构建重新生成并验证（该生成文件不纳入 Git）。
- 以真实宿主继承链探测 Compose，首次初始化后通过帧回调后的主线程消息采集。后台沿用 5 秒超时，过期请求与回调取消，后续请求不重复 hot reload。
- Dragonfly error/warning 写入节点 message，snapshot 聚合可选 warnings。dump 按输出子树与 GONE 过滤收窄诊断；HTML 保留并转义诊断节点，摘要明确报告不完整。
- find、inspect、元素 tap 保留有效匹配并携带诊断；未命中且窗口采集不完整时返回 `UI_HIERARCHY_INCOMPLETE`。原预算 truncated、正常结果与 selector 参数保持原语义。
- 同步知识库、Dragonfly 打包说明、UI 自动化与 MCP 参考的中英文 Wiki。CLI 升至 `1.0.18`、skill 升至 `1.0.32`，使诊断说明进入插件分发包。

### 验证证据

- 先执行四个新增失败行为测试，分别复现 JSON 丢失 message、HTML 隐藏诊断、缺少不完整错误码、tap 将不完整采集误判为 NotFound；修复前均按预期断言失败。另一个先失败测试复现 rootLayout/GONE 过滤后仍混入范围外诊断。
- 测试价值判断：这些断言保护可观察序列化、有效内容保留和错误语义；复用 L1 `DragonflyHierarchySourceTest`，以及既有 HTML、MCP action、client、错误诊断 owner。不通过反射私有状态或新增生产 seam 模拟 App ClassLoader。
- 定向回归：Agent 侧 `DragonflyHierarchySourceTest`、`ViewHierarchyServerLoaderTest`、`BootstrapApplicationTest`、`BootstrapAppComponentFactoryTest` 共 19 项；main 侧 HTML、dump、client、failure diagnoser、inspect、tap、locate、生成脚本 8 个 owner 共 113 项，全部通过。
- L3 已有 `ReadProjectInfoGradle9CompatTest.generatedScript_shouldMinifyReleaseWithBundledRuntime` 构建真实无 Compose 的 Java minified APK，并验证反射服务入口存在。最初暴露无条件保留 adapter 会使纯 Java 宿主缺少 Compose 类；改为条件 keep 后构建通过，不增加全局 dontwarn 或宿主 Compose 依赖。环境初次缺少 ANDROID_HOME 已补齐后重新验证。
- `:idea:compileKotlin`、`:idea:buildPlugin` 通过。实际 SDK dexdump 检查 instruments：26 个 class definitions，ViewHierarchy/Dragonfly 定义均为 0，BootstrapApplication 保留；runtime 中入口、adapter、私有 Kotlin 存在，原 Dragonfly/Kotlin 命名空间不存在。
- `validate_wiki.py`、production `npm run build`、4 个修改页面的生成 HTML 诊断文本检查通过；`docs-skills.zip` 中版本与错误说明同步。
- 本地验证日志：`/tmp/jugg-compose-red.log`、`/tmp/jugg-compose-scope-red.log`、`/tmp/jugg-compose-validation.log`、`/tmp/jugg-compose-wiki.log`、`/tmp/jugg-compose-minify.log`、`/tmp/jugg-compose-final-build.log`、`/tmp/jugg-compose-artifacts-final.log`。日志为本机临时证据，不随仓库分发。

产物指纹：

| 产物 | SHA-256 |
|------|---------|
| 新 instruments JAR | `afe85e8dac813b6e32d315dee18016988276a6d61b75239bb5d6bd6bd07654ee` |
| 新 runtime JAR | `771327a823e837e1a8785f23f78bca82a605e463d44f5295a74175275ee47527` |
| 插件 `jugg-3.5.4-release.zip` | `bc7bbb83521218fc15fd4da761f38985283258f5a3069df412367187fa495cd1` |

### 未完成的现场验收

实施期间 `adb devices -l` 没有设备，未安装插件到当前 IDE，也未操作 JOJO 业务代码。上述本地通过不能证明 JOJO Compose 已恢复：第 5.2 节的实际加载器、同页 screenshot/dump/定位、冷启动首次帧、动态时钟、悬浮入口点击、混合多窗口、空宿主、兼容性失败及 compat 启动均待目标设备验收。新 APK 指纹也尚未取得。升级后必须用新插件完整 Gradle 构建安装新 APK，再按该矩阵收集证据。

### 实际依据

- `docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`：工程边界与实现定位。
- `09_plugin_runtime_debug.md`：原始日志窗口与运行时证据要求。
- `03_runtime_jvmti.md`：bootstrap 加载、runtime 打包与 Agent 缓存版本。
- `08_mcp_design.md`、`08_mcp_layout_verify_design.md`、`08_mcp_tools_list.md`：结果协议、采集与错误行为。
- `06_testing.md`：测试价值门禁、定向回归与真机证据边界。
- `08_cli_tools_list.md`：CLI / skill 版本与分发要求。
- `10_wiki_authoring.md`、`10_wiki_architecture.md`、`.agents/skills/wiki-writer/SKILL.md`：Wiki 写作、中文基准镜像与 production 验证。
- 用户报告、原始 JOJO dump 与同任务历史日志、设备旧 instruments DEX、native `AddToBootstrapClassLoaderSearch` 和 Dragonfly binary API/错误节点反汇编，详见第 1 节。文档中的建议只作为调查资料，实施范围由用户批准的本方案确定。

## 8. 后续现场：宿主 Kotlin ABI 重定位修复

### 失败证据与规则确认

- 加载边界修正后的现场为 JOJO `build/jugg/mcp_fetch/layout-dump/layout_1791990123320.json` 与 HTML；同任务 `build/jugg/log/compile_latest.log` 在 `2026-10-14 23:02:03.454` 记录了 5 个节点及提取异常。异常已经通过 warnings 对外保留，但 Compose 内容仍未恢复。
- 原始 Dragonfly 调用 `SlotTreeKt.mapTree$default(CompositionData, kotlin.jvm.functions.Function3, ContextCache, int, Object)`；旧预处理产物将第二个参数和回调工厂的返回接口都改成私有 Function3。现场 APK `classes18.dex` 的方法定义及 JOJO 现场缓存的 tooling-data JAR 使用标准 Kotlin Function3，其余参数一致，因此本次原因是错误重定位，不能归因于宿主没有 mapTree 方法。
- 扫描原始源码 JAR 全部宿主 Compose 方法引用，仅 mapTree 的描述符包含 Kotlin 类型；Function3 在该源码 JAR 中也仅由 `ComposeExtractorKt.class` 使用。这个回调由 `invokedynamic` 工厂创建，没有继承私有 Kotlin Lambda 的桥接需求。

### 最小修复

- 新增 `jarjar-host-api-rules.txt`：精确 identity rule `kotlin.jvm.functions.Function3 -> kotlin.jvm.functions.Function3`，且必须排在 `kotlin.**` 通配规则之前。
- `preprocess.sh` 仅为 Dragonfly 源码 JAR 拼接上述规则；stdlib 和 implementation 仍只用原公共规则，将全部运行库定义和引用私有化。不能对两个输入统一排除 Function3，也不能只还原宿主调用描述符却保留私有回调接口。
- 更新提交的 `dragonfly_0-jugg.jar`；逐 entry 比较确认仅 `ComposeExtractorKt.class` 内容改变，其他条目和类集合相同。`implementation_0-jugg.jar` 保持原提交内容，不混入本次无需修改的 dex2jar 再生成结果。
- Agent 从 `1.0.83` 升至 `1.0.84`。不新增反射桥接、宿主业务改动或全局 dontwarn。

### 验证与交付

- 测试价值：宿主方法描述符、可赋给宿主的回调接口、运行库命名空间是实际链接契约，由新增 L1 `DragonflyRuntimeAbiTest` 检查最终 `/deploy/jugg-runtime.jar`。不增加生产 mock seam；新增的两项测试在修复前均断言失败，修复后通过。
- 使用 JOJO 日志列出的本地真实 Compose 依赖，独立 JVM 加载原 Dragonfly 与私有运行库，调用公开 `buildTree`：旧 JAR 稳定复现现场同一个 NoSuchMethodError；新 JAR 和最终合并 runtime 均实际完成宿主 mapTree、Dragonfly 回调并生成 `ComposeNodeContext`，组成单组的 CompositionGroup 已被读取。该验证证明宿主 API/回调链接，不替代 Android 页面采集验收。
- 定向回归共 135 项通过：Agent 19 项；main 原有 113 项、新增 ABI 2 项及既有纯 Java minified APK L3 1 项。无宿主 Compose/Kotlin 的真实 R8 构建通过；`:idea:compileKotlin`、`:idea:buildPlugin` 和脚本语法检查通过。
- 分发包内 runtime 与构建输出字节一致，并含 `jugg-agent-bundle-1.0.84.zip`；instruments SHA-256 与上次相同，采集链仍只在 APK runtime。临时日志：`/tmp/jugg-compose-abi-red.log`、`/tmp/jugg-compose-abi-preprocess.log`、`/tmp/jugg-compose-abi-host-red.log`、`/tmp/jugg-compose-abi-host-green.log`、`/tmp/jugg-compose-abi-host-final.log`、`/tmp/jugg-compose-abi-final.log`。
- 同步 `03_runtime_jvmti.md`、`08_mcp_layout_verify_design.md` 和 Dragonfly README。Wiki 的用户操作、结果与升级步骤没有改变，不新增内部 ABI 细节。

| 本次最终产物 | SHA-256 |
|--------------|---------|
| `dragonfly_0-jugg.jar` | `015aa0f57df1e84492a43d9b3b9b228de100105c0dac8cefe61365ad49e01988` |
| `jugg-runtime.jar` | `b803eadc8c3889ff92a797988336d0f0c74cd6c8991e0167b7870086b1edd848` |
| `jugg-instruments.jar` | `afe85e8dac813b6e32d315dee18016988276a6d61b75239bb5d6bd6bd07654ee` |
| `jugg-3.5.4-release.zip` | `048df53c239844fdff1bebba1dc5592dd17e3874f464226aaade62ef65f8aaa3` |

本次未安装插件到当前 IDE、未重新构建安装 JOJO、未执行设备同页 dump/定位；第 7 节旧产物指纹保留为历史记录，后续验收应使用本节 Agent 和 runtime。需安装本次插件，再完整 Gradle 构建安装新 APK 后冷启动，旧 APK 的重启或仅替换 bootstrap agent 无法更新这里修正的 APK runtime。

本次依据沿用第 7 节实际文档清单；新增现场依据为上述同任务 dump/日志、原始与预处理 Dragonfly 字节码、宿主 DEX/API 定义和真实宿主调用的失败/成功证据。外部只读调查阶段参考 `.agents/skills/issue-handler/references/local-agent-investigation-guide.md`；后续实施由用户明确授权，不将附件中的建议当作指令。
