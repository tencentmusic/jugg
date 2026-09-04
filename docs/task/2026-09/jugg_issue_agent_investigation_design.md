# Jugg 用户 Agent 问题调查方案

## 1. 背景

Jugg 问题反馈的首轮证据经常不完整。用户可能安装了 Jugg skill、持有本地 Jugg 仓库，也可能只有能够访问公开仓库的 Agent。不能要求用户理解不同环境、手工填写固定 Prompt，或自行判断应该提供哪些日志和产物。

本方案提供一个公开、版本化的 Agent 调查入口。用户只需向 Agent 提供该入口 URL，Agent 自动定位工程、读取现场、加载对应 Jugg 版本的官方知识并按已确认的安全等级交付结果。

## 2. 目标

- 用户只需发送一个公开 URL；URL 本身就是完整 Prompt。
- 用户仅发送 URL，或附带“看下”“排查一下”“反馈一下”等简短表达时，Agent 都应直接开始调查，而不是总结文档或要求用户重新填写信息。
- Agent 优先从当前对话、当前工作区、Jugg MCP 和现有日志自动获取问题上下文。
- Agent 使用用户现场的实际 Jugg 版本读取对应 tag 的文档和源码，避免用当前 `main` 替代用户现场。
- 根据已缓存的安全等级分别交付文本报告、Report ID 或完整现场包。
- 缺少部分信息时 Best-effort 保留已有结果，并明确证据边界。

## 3. 启动前提

以下能力是本方案的前置条件，由独立任务完成迁移或实现，不属于本方案范围：

- `jugg pre-report` 能通过 HTTP 请求现有 Jugg MCP，返回实际 Jugg/Runtime 版本、目标工程、缓存的安全等级和必要的结构化现场摘要。
- 安全等级确认结果根据规范化后的项目绝对路径缓存在 `~/.jugg/report` 下。
- 缓存缺失、损坏、策略版本变化或权限提升时，由 `pre-report` 负责要求用户重新确认。
- NORMAL 等级能够复用标准 Jugg Report 流程上传脱敏诊断包并返回 Report ID。

`investigate_jugg_issue.md` 只消费上述结果，不实现 MCP 发现、权限确认或缓存。

## 4. 公开入口

新增公开文档：

```text
tools/investigate_jugg_issue.md
```

用户只需发送：

```text
https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/investigate_jugg_issue.md
```

以下输入均视为同一请求：

```text
https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/investigate_jugg_issue.md
```

```text
看下
https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/investigate_jugg_issue.md
```

```text
反馈一下
https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/investigate_jugg_issue.md
```

不再维护一份独立的用户 Prompt。所有激活规则、调查步骤和交付格式都内联在该文档中。

## 5. Agent 激活规则

`investigate_jugg_issue.md` 开头必须明确要求 Agent：

1. 用户提供本文或本文 URL 时，立即执行调查流程，不总结本文。
2. 不要求用户重新填写问题描述、Jugg 版本、工程路径、安全等级或环境信息。
3. 优先从当前对话提取用户已经描述的操作、实际表现和预期结果。
4. 优先使用当前工作区，通过 `pre-report` 和 `build/jugg` 判断目标工程。
5. 只有无法唯一确定目标工程，或 `pre-report` 明确要求首次授权时，才向用户提问。
6. 用户主观预期无法从上下文推断时，在最终报告中标记“用户未明确说明”，不阻塞现场采集。

## 6. 总体流程

```text
用户提供 investigate_jugg_issue.md URL
  -> Agent 调用 jugg pre-report
  -> 获得目标工程、实际 Jugg 版本和安全等级
  -> 判断现场是否仍有效
  -> 读取对应版本的官方排查文档
  -> 锁定问题时间窗和症状路由
  -> 读取单个相关专题、代码地图和真实实现
  -> 执行反证检查
  -> 按 STRICT / NORMAL / FULL 交付
```

## 7. 工程与问题自动发现

Agent 按以下顺序确定调查对象：

1. 从当前对话读取用户刚描述的问题、操作和时间信息。
2. 以当前工作区为首选工程调用 `jugg pre-report`。
3. 使用 `pre-report` 返回的已初始化工程和当前工程匹配结果。
4. 无法匹配时，在当前工作区内查找 `build/jugg`，禁止扫描整个用户主目录。
5. 只命中一个工程时直接使用；多个工程均可能匹配时才请求用户选择。
6. 找不到 `build/jugg` 时仍生成有限报告，明确没有可用的 Jugg Runtime 现场。

Agent 不应优先询问用户工程路径、Jugg 版本或日志位置。

## 8. 现场有效性检查

任何调查开始前，Agent 禁止执行可能覆盖现场的操作：

- Run、Build、Compile、Deploy、Restart 或重新安装。
- Clear Jugg Build、清数据或删除缓存。
- 修改源码、Gradle、配置、资源或 Manifest。
- 为了复现问题主动改变设备或 App 状态。

Agent 先记录：

- `build/jugg/log` 下真实日志文件的修改时间和大小。
- `compile_latest.log`、`compile_latest-1.log` 与真实滚动日志的对应关系。
- 当前时间、Git HEAD 和变更文件类型。
- 数据库、MCP artifact、staging 等场景证据是否存在。

如果日志或产物时间晚于用户描述的问题，必须说明现场可能已被后续操作覆盖，不得把最新文件直接当作问题现场。

## 9. 版本化官方知识加载

Agent 使用 `pre-report` 返回的实际 Jugg 版本，优先读取对应 tag：

```text
https://raw.githubusercontent.com/tencentmusic/jugg/<version>/docs/ai_knowledge/09_plugin_runtime_debug.md
https://raw.githubusercontent.com/tencentmusic/jugg/<version>/docs/ai_knowledge/99_index.md
https://raw.githubusercontent.com/tencentmusic/jugg/<version>/docs/ai_knowledge/98_code_map.md
```

固定顺序：

1. 从 `09_plugin_runtime_debug.md` 获取证据边界、时间窗定位、症状路由和反证门禁。
2. 从 `99_index.md` 选择与症状直接相关的单个专题，禁止一次性加载全部知识库。
3. 从 `98_code_map.md` 定位入口类、症状 owner 和候选 behavior owner。
4. 读取对应版本的专题文档和真实实现，确认行为分支。
5. 使用对应 tag 的 GitHub URL 引用代码依据。

源码引用格式：

```text
https://github.com/tencentmusic/jugg/blob/<version>/<source-path>#L<line>
```

对应 tag 不存在或相关文件不可用时，才读取 `main`，并在报告中将实现判断标记为跨版本推断。

## 10. 调查流程

### 10.1 锁定时间窗

时间锚点按以下优先级选择：

1. 当前对话中的准确发生时间。
2. 用户最后一次操作对应的 `Jugg compile started`。
3. 最近一次完整异常块。
4. 都不存在时使用最新任务，并明确这是推断时间窗。

Agent 从真实 `compile_*.log` 向前后扩展同一任务上下文，确认：

- 用户操作对应的任务入口。
- 编译、部署或运行阶段。
- 首个原始异常或失败状态。
- 回退、恢复、重试和最终状态。

最终汇总错误、CLI 文案或 UI 提示只能作为派生结果，不能替代原始异常。

### 10.2 按症状补充区分性证据

Agent 不收集固定的大型环境表，只收集能够区分竞争解释的证据：

| 症状 | 首要补充证据 |
|---|---|
| 编译失败或异常回退 | changed files、增量判定、首个编译异常、Gradle 回退原因 |
| 部署、安装或启动失败 | deploy 类型、设备状态、recover/retry、ADB 或 installer 原始结果 |
| 改动未生效 | 编译产物、deploy data、部署类型、App 是否重启 |
| Runtime crash | crash 时间、部署完成时间、logcat、staging/APK DEX 关系 |
| Debug 失败 | WAITING、VM connected、debug session 创建三个阶段 |
| IDE 卡顿或卡死 | 同一时间窗的 Jugg 日志、`idea.log`、freeze dump 和线程栈 |
| 远程编译失败 | full log、同步 diff、远端命令终态 |
| CLI/MCP 问题 | 请求参数、原始响应、artifact、CLI 与 MCP 的转换边界 |

设备、Activity 栈、layout 或其他观察工具只在当前假设需要时使用，不因为可能有用而默认执行。

### 10.3 定位 behavior owner

Agent 必须区分：

- 症状 owner：打印错误、展示文案或汇总状态的组件。
- behavior owner：真正决定失败分支、状态迁移或兼容行为的组件。

找到日志 `[ClassName]` 后，继续沿对应版本的源码调用链定位 behavior owner，不能直接把日志来源类作为根因 owner。

### 10.4 反证门禁

输出结论前必须完成：

1. 写出领先解释及其直接证据。
2. 确定最强竞争解释。
3. 写出能够推翻或显著削弱领先解释的可观察证据。
4. 在已授权的现场、日志、源码和历史中主动检查该证据。
5. 无法解释冲突时降低结论强度，不得输出确定根因。

## 11. 安全等级

安全等级控制 Agent 对外提交什么，不限制 Agent 为完成本地诊断而读取相关现场。任何等级下，凭据、Token、密码、Cookie、SSH、签名信息和其他秘密都不得出现在对外交付内容中。

### 11.1 STRICT

Agent 可以正常读取并分析本地现场，包括日志、项目配置、必要源码、数据库和构建或部署产物。

对外只交付一份整理后的文本诊断报告：

- 不调用 Report 上传。
- 不生成或提交 Report ID。
- 不上传 ZIP、日志、APK、class、数据库或其他产物。
- 不直接复制完整日志或私有源码。
- 项目名、模块名、包名、用户名、绝对路径、内部依赖和内部地址必须匿名化。
- 报告只保留能支撑结论的最小脱敏事实和公开 Jugg 源码依据。

### 11.2 NORMAL

Agent 完成与 STRICT 相同的本地调查和文本报告，并额外：

- 执行标准 Jugg Report 流程。
- 上传现有脱敏 Report Bundle。
- 获取 Report ID，并在文本诊断报告中附上该 ID。
- 不收集或提交 Debug APK、部署 class 等完整现场产物。

Report 上传失败时保留本地调查结果，Report ID 标记为不可用，不得让上传失败阻断文本报告。

### 11.3 FULL

Agent 不再自行设计完整产物采集逻辑，直接读取并完整执行：

```text
https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/collect_jugg_scene_prompt.md
```

FULL 沿用现有完整现场采集行为：

- 生成 `jugg_scene_*` 目录和 ZIP。
- 收集 Debug APK、staging、compiled、数据库、设备 APK/overlay 等现场。
- Agent 不把二进制或完整产物内容写入对话。
- 用户将生成的 ZIP 交付维护者。

完整采集局部失败时，沿用 `collect_jugg_scene_prompt.md` 的 Best-effort 行为，保留已经采集的结果并说明缺失项。

## 12. STRICT/NORMAL 报告格式

```markdown
# Jugg 问题诊断报告

## 现场
- Jugg 版本：
- 问题时间窗：
- 现场是否可能被覆盖：
- 安全等级：
- Report ID：仅 NORMAL

## 用户可见问题
- 操作：
- 实际结果：
- 预期结果：

## 证据链
1. 原始现场证据及时间
2. 相关任务状态变化
3. 对应版本的 Jugg 文档和源码依据

## 诊断
- behavior owner：
- 领先结论：
- 直接依据：
- 竞争解释：
- 反证结果：
- 结论强度：

## 证据边界
- 已检查但不存在：
- 无法获取：
- 未能确认：
- 未对外提交的敏感材料：
```

不能从当前对话或现场确认的用户主观信息直接标记为未知，不要求用户为了填满模板重新描述全部问题。

## 13. 降级策略

- `pre-report` 或 MCP 不可用：不启动或初始化新的 Runtime；基于当前工作区和已有现场继续调查，并说明无法自动确认的版本或安全等级。没有可用安全等级时不得上传任何材料，按 STRICT 交付。
- `build/jugg` 不存在：收集可确认的环境、工程和用户行为信息，明确没有 Jugg Runtime 现场。
- Report 上传失败：按 NORMAL 继续输出文本报告，Report ID 标记为不可用。
- 对应版本 tag 不存在：使用 `main` 作为辅助资料，并明确跨版本推断边界。
- 某类辅助证据读取失败：只舍弃该证据，不放弃其余有效调查结果。
- FULL 采集失败：沿用完整采集文档的局部降级，不退回 Agent 自制的另一套完整采集实现。

## 14. 实施范围

本方案后续实施仅包括：

- 新增 `tools/investigate_jugg_issue.md`。
- 更新 `docs/ai_knowledge/09_plugin_runtime_debug.md`，增加用户 Agent 调查入口和三档交付关系。
- 检查 `99_index.md`、`98_code_map.md` 是否需要增加新入口。

不包括：

- `pre-report` 的迁移或实现。
- 授权确认与 `~/.jugg/report` 缓存实现。
- MCP、CLI、Report Bundle 或 IDEA Report UI 修改。
- 修改 `tools/collect_jugg_scene_prompt.md` 或完整采集脚本。
- Issue 模板和 Issue Handler 自动化调整。

## 15. 验证方案

该能力的风险是 Agent 未按流程调查、错误使用现场版本、改变现场或泄露敏感内容。普通源码字符串测试无法证明这些行为，使用真实 Agent 场景作为主要验证：

| 场景 | 预期结果 |
|---|---|
| 用户只发送 URL | Agent 直接执行，不总结文档或要求用户填写 Prompt |
| 用户说“看下”并发送 URL | 自动读取当前对话和工作区 |
| 无 Jugg skill、无本地 Jugg 仓库 | 通过公开 URL 加载对应版本知识 |
| 日志已被后续 Run 覆盖 | 明确现场可能失效，不伪造问题时间窗 |
| STRICT | 正常本地调查，仅输出脱敏文本，不上传任何产物 |
| NORMAL | 输出文本报告并提供 Report ID，不提交 APK/class |
| NORMAL 上传失败 | 保留完整文本报告并明确 Report ID 不可用 |
| FULL | 完整转交 `collect_jugg_scene_prompt.md`，不重新实现采集 |
| 对应 tag 不存在 | 回退 `main` 并标记跨版本推断 |
| 日志含凭据、绝对路径和内部标识 | 对外交付内容中不出现原值 |

文档实施完成后执行：

- `git diff --check`
- Markdown 链接和路径抽查
- 使用至少一组 STRICT、一组 NORMAL 和一组 FULL Agent 会话验证实际工具轨迹与交付结果

## 16. 非目标

- 本方案不保证 Agent 在证据不足时直接定位根因。
- 不要求所有场景收集相同字段或产物。
- 不通过增加固定表格替代按症状选择区分性证据。
- 不要求用户预先理解 Jugg skill、日志路径、仓库结构或报告协议。
- 不允许 Agent 为获得更完整的现场主动重跑或修改工程。
