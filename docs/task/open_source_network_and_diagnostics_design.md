# Jugg 开源发行网络隔离与诊断上传方案

> 状态：方案已确认，等待实现授权  
> 最后核对：2026-07-30  
> 一致性规则：文档与代码冲突时，以代码为准。

## 1. 文档定位

本文定义 Jugg 开源发行前的网络能力隔离与问题诊断上传方案，覆盖：

- 外网发行包与内网发行包的构建边界。
- 自动后台能力在不同发行包中的启用规则。
- 日志上传使用独立 URL 的交互和安全约束。
- 诊断包文件白名单、脱敏、清单展示和失败回退。
- 与风险匹配的测试矩阵、实施顺序和文档同步项。

本文不实现后台服务，不选择第三方上传服务商，不调整编译、部署、CLI、MCP 的本地行为，也不在本轮修改生产代码。

## 2. 已确认决策

| 决策 | 结论 |
|---|---|
| 外网发行包 | 不包含服务器链接，无 Jugg 自动网络行为，默认离线运行 |
| 内网发行包 | 构建时注入内网服务器配置，保留现有后台能力 |
| 问题日志上传 | 使用独立的完整上传 URL，不复用更新、配置或热更新服务器地址 |
| 上传 URL 默认值 | 外网包默认为空或上次用户输入；内网包由构建配置提供默认值 |
| 上传前确认 | 展示最终目标 URL、实际文件清单、大小、敏感等级和脱敏状态 |
| 上传内容 | 只允许白名单生成的诊断文件，禁止直接压缩现有项目模型和临时目录 |
| 上传失败 | 不切换其他服务器；保留本地诊断包，允许用户重试同一 URL |

“外网包纯离线”的准确含义是：**无预置后端、无自动联网**。用户主动输入 URL 并确认上传属于一次显式授权，不视为破坏默认离线边界。

## 3. 当前行为与缺口

### 3.1 当前后台职责耦合

`JuggServer` 当前同时负责：

- 事件上报。
- 插件更新检查。
- 热更新检查与文件下载。
- 项目配置下发。
- 问题日志上传。
- 远端编译机器申请交互。

`JuggServer` 构造后还会触发 `JuggServerChooser.updateServerIfExpired(isForce = true)`，因此即使没有执行编译或上传，也可能发生服务器选择、DNS 解析和连通性探测。

### 3.2 当前服务器地址进入公共产物

`main/src/main/resources/config/servers.json` 当前包含真实内网域名和公网 HTTP 地址。公开源码和外网插件包都不应携带这些地址。

### 3.3 当前上传范围超过确认文案

`JuggServer.reportAndUploadLogs()` 当前会打包：

- Jugg 编译和部署日志。
- 设备错误 logcat。
- `projectInfosDir`。
- `remoteDiffDir`。
- `tmpGradleProjectInfo`。
- 全局 `jugg-hook-debug.log`。

确认弹窗只声明日志和 `project_infos`，没有逐项展示实际 zip 内容，也没有说明目标服务器和脱敏状态。

### 3.4 原始项目模型不适合上传

`ModuleInfo` 包含绝对路径、Manifest placeholders、APT/KAPT 参数、applicationId、namespace 和签名配置。`SigningConfig` 进一步包含：

- keystore 路径。
- `storePassword`。
- `keyAlias`。
- `keyPassword`。

因此，无论上传目标是内网还是外网，都不能继续直接上传原始 `project_infos`。

### 3.5 当前上传存在静默 fallback

`JuggServerChooser.getUploadServerUrls()` 会把当前服务器和内置服务器组合为候选列表。用户确认一次上传后，实际请求可能依次尝试多个目标地址，不符合“同意必须绑定明确目的地”的要求。

## 4. 目标与非目标

### 4.1 目标

- 外网插件包中不出现内网域名、默认后台地址和后台探测规则。
- 外网插件启动、编译、部署、CLI 和 MCP 本地流程不触发 Jugg 自动网络请求。
- 内网插件由私有构建环境注入后台配置，不把内网地址提交到公开仓库。
- 问题日志上传与其他后台能力使用不同配置和调用链。
- 用户确认的目标 URL 与实际请求目标严格一致。
- 用户看到的文件清单与最终 zip entry 完全一致。
- 敏感数据通过结构化白名单从源头排除，而不是只依赖正则替换。
- 上传失败不影响本地编译部署，也不丢失诊断包。

### 4.2 非目标

- 本方案不重写内网后台接口。
- 本方案不新增自动遥测或公开发行版统计能力。
- 本方案不设计账号体系、OAuth 或上传服务端鉴权。
- 本方案不允许公开发行版继续使用后台 jar 热更新或远程自定义编译器下发。
- 本方案不把 GitHub Issue 作为私有诊断包存储位置。

## 5. 方案比较

| 方案 | 正确性与信任 | 实现成本 | 主要问题 | 结论 |
|---|---:|---:|---|---|
| 所有发行包共用后台，增加关闭开关 | 低 | 低 | 外网包仍携带内部地址，初始化仍可能联网 | 排除 |
| 运行时判断内外网后自动切换 | 低 | 中 | 行为不可预测，可能误连内网或错误启用后台能力 | 排除 |
| 构建期区分外网/内网包 | 高 | 中 | 需要新增产物守卫和私有 CI 注入 | 采用 |
| 外网包完全删除上传能力 | 高 | 低 | 用户无法便捷提交私有诊断包 | 不采用 |
| 外网包无默认 URL，用户主动填写后上传 | 高 | 中 | 需要清晰交互、白名单和脱敏 | 采用 |

## 6. 发行包设计

### 6.1 构建期发行配置

新增构建期发行配置，至少表达：

```text
distribution = public | internal
backendServerUrl = <optional>
reportUploadUrl = <optional>
```

推荐由构建任务生成只读发行配置，生产代码只消费生成结果。不要在公开源码中维护一份带真实 URL 的 `servers.internal.json`。

配置来源建议：

- 公开 CI：固定生成 `public` 配置，两个 URL 都为空。
- 内网 CI：从私有环境变量、私有 Gradle properties 或私有资源 overlay 注入。
- 本地开发：默认等同 `public`；开发者明确指定时才生成 `internal`。

构建约束：

- `public` 构建发现非空后台 URL 时必须失败。
- `internal` 构建缺失必须的后台 URL 时必须失败，不静默退化成未知状态。
- 公开构建产物必须扫描并确认不包含已知内网域名和默认服务器地址。

### 6.2 能力矩阵

| 能力 | 外网包 | 内网包 |
|---|---|---|
| 自动事件上报 | 禁用 | 保留现有策略，后续独立审计 |
| Jugg 后台更新检查 | 禁用 | 启用 |
| 项目配置下发 | 禁用 | 启用 |
| jar 热更新 | 禁用 | 保留现有策略，后续补供应链安全 |
| 远程自定义编译器下载 | 禁用 | 保留现有策略，后续补供应链安全 |
| 远端机器申请 | 不提供默认入口 | 按内网配置启用 |
| 主动问题报告 | 可用，URL 默认空 | 可用，URL 提供内网默认值 |
| 编译、部署、CLI、MCP 本地能力 | 正常可用 | 正常可用 |

### 6.3 初始化边界

外网包中：

- 不加载服务器候选列表。
- 不执行 `InetAddress.getByName()` 或 `isReachable()`。
- 不启动更新、热更新或远程配置协程。
- 不因构造 `JuggServer` 或等价对象产生任何网络副作用。

内部实现不应通过散落的 `if (distribution == public)` 保护每个请求。推荐在装配阶段不启动对应能力，避免漏掉新的后台调用点。

## 7. 问题报告职责拆分

### 7.1 独立上传地址

新增独立设置：

```text
reportUploadUrl
```

它只允许用于问题诊断包上传，不得影响：

- 更新检查 URL。
- 自动事件上报 URL。
- 热更新下载 URL。
- 项目配置下发 URL。
- 自定义编译器下载 URL。

输入框建议要求用户填写完整上传 endpoint，例如：

```text
https://example.com/report_issue
```

不再由客户端隐式拼接 `/report_issue`，避免用户不知道最终请求路径。

### 7.2 URL 校验

上传前执行：

- 去除首尾空格。
- 必须是绝对 URL。
- 默认只接受 HTTPS。
- 禁止 URL user-info，即 `https://user:password@example.com`。
- 禁止 fragment。
- 首版禁止通过 query 传 token、密码等凭据。
- HTTP 只允许显式开发模式或内网兼容模式，并展示明文传输警告。

### 7.3 默认值与持久化

- 外网包首次打开时默认值为空。
- 外网包可记住用户上次成功使用的 URL，但必须在每次上传前展示。
- 内网包首次打开时使用构建期 `reportUploadUrl`。
- 用户修改内网默认值后，仅影响问题上传，不改变其他后台能力。

## 8. 诊断包设计

### 8.1 白名单目录结构

最终诊断包固定为：

```text
diagnostics/
├── manifest.json
├── environment.json
├── project-summary.json
├── logs/
│   └── compile_<timestamp>.log
├── device/
│   └── logcat.log
└── optional/
    └── hook-debug.log
```

所有文件都必须由诊断包 builder 主动创建或复制，禁止把任意现有目录直接加入 zip。

### 8.2 默认包含内容

| 文件 | 内容 | 默认选择 | 处理要求 |
|---|---|---:|---|
| `manifest.json` | 实际 entry、大小、分类、脱敏结果 | 是 | 由最终打包清单生成 |
| `environment.json` | 插件版本、IDE 大版本、OS、JVM、目标 API 等 | 是 | 结构化白名单 |
| `project-summary.json` | 模块数量、模块类型、构建 variant、AGP/Kotlin 等诊断信息 | 是 | 禁止复用原始 `ModuleInfo` 序列化 |
| `compile_<timestamp>.log` | 用户选择的 Jugg 日志 | 是 | 路径和已知敏感值脱敏 |
| `logcat.log` | 目标设备错误日志 | 是 | 标记高敏感，允许取消 |
| `hook-debug.log` | Agent hook 调试日志 | 否 | 单独选择并提示可能跨项目 |

### 8.3 永久禁止进入诊断包

- `SigningConfig.storePassword`。
- `SigningConfig.keyPassword`。
- SSH 密码、私钥、access token、cookie。
- 原始 `project_infos` 目录和完整项目模型。
- Manifest placeholders 原始键值。
- APT/KAPT 参数原始键值。
- `remoteDiffLibraryDir` 中的 jar、aar 和其他二进制依赖。
- `tmpGradleProjectInfo` 原始文件。
- keystore 文件。
- 源码、资源文件和构建脚本正文。

以上内容属于硬边界，不因用户勾选或内网发行包而放开。

### 8.4 脱敏规则

结构化数据优先从安全 DTO 生成，不先序列化完整对象再删字段。

文本日志至少替换：

- 项目绝对路径 -> `${PROJECT_DIR}`。
- 用户目录 -> `${USER_HOME}`。
- Git 用户名和邮箱 -> 固定占位符。
- 已知远端主机、用户名和密码 -> 固定占位符。
- 签名配置中已知密码和 keystore 路径 -> 固定占位符。

通用正则只能作为第二层保护，不能代替数据源白名单。脱敏失败时应阻止上传并保留本地原始文件，不允许“尽力而为后继续上传”。

### 8.5 `manifest.json`

建议字段：

```json
{
  "schemaVersion": 1,
  "reportId": "local-generated-id",
  "createdAt": "2026-07-30T12:00:00+08:00",
  "entries": [
    {
      "path": "diagnostics/logs/compile_2026-07-30.log",
      "category": "jugg_log",
      "size": 1024,
      "sensitivity": "medium",
      "redaction": "completed",
      "selected": true
    }
  ]
}
```

manifest 必须基于最终待压缩 entry 生成。打包完成后再次读取 zip entry 与 manifest 对比，不一致则禁止上传。

## 9. 用户交互流程

```text
用户点击 Report Issues
  -> 插件生成候选诊断文件并执行脱敏
  -> 打开报告确认窗口
       上传 URL
       实际文件清单
       每项大小、敏感等级、脱敏状态
       总压缩大小
       打开本地文件/目录
       可取消非必要文件
  -> 用户确认
  -> 按最终选择重新生成 manifest 和 zip
  -> 校验 manifest 与 zip entry 一致
  -> 只向确认的唯一 URL 上传
  -> 成功：展示并复制 Report ID
  -> 失败：保留 zip，展示错误并允许重试同一 URL
```

交互要求：

- URL 变更后必须重新确认，不能沿用旧确认状态。
- 文件选择变更后必须重新生成 zip 和 manifest。
- 上传开始后不能自动增加新文件。
- 不提供“失败后尝试默认服务器”的隐式逻辑。
- 用户可以只保存本地诊断包而不上传。

## 10. 所有权变化

推荐最小职责拆分：

| 所有者 | 责任 |
|---|---|
| 构建期发行配置 | 生成 public/internal 只读配置，保证公开产物不带后台地址 |
| `IssueReportBundleBuilder` | 生成白名单诊断文件、脱敏、manifest 和 zip |
| `IssueReportUploader` | 校验并上传到单一 `reportUploadUrl`，解析 Report ID |
| Report Issues UI | 展示 URL、清单、敏感等级、选择状态和结果 |
| 现有 `JuggServer` | 移除问题诊断包打包职责；内网其他后台能力后续再拆分 |

不为每个类额外抽象只有一个实现的方法接口。只有当业务语义确实需要替换外部上传实现时，再引入符合仓库命名规范的接口。

## 11. 失败与回退策略

| 失败场景 | 行为 |
|---|---|
| URL 为空或非法 | 不生成网络请求，停留在确认窗口 |
| 脱敏失败 | 禁止上传，提示具体失败文件 |
| manifest 与 zip 不一致 | 禁止上传，保留本地证据 |
| 连接超时或服务端失败 | 不切换服务器，保留 zip |
| 服务端未返回 Report ID | 视为上传失败或明确显示“已上传但无 ID”，由协议决定 |
| 用户取消 | 删除临时副本或保留用户主动保存的诊断包，不发送请求 |
| 插件退出 | 不后台续传，不在下次启动自动重试 |

上传失败永远不能改变编译、部署或项目状态。

## 12. 兼容与迁移

- 旧 `JuggSettings.serverUrl` 只继续服务内网后台控制面。
- 新增 `reportUploadUrl` 后，不从旧 `serverUrl` 自动迁移到外网包。
- 内网包可以在首次运行时把构建默认上传 URL 作为 UI 默认值，但不需要持久化成用户覆盖值。
- 删除公开资源中的 `servers.json` 真实地址；如保留文件，公开版本内容必须为空列表。
- 移除 `reportAndUploadLogs()` 对原始目录的直接打包，避免新旧入口并存。
- 公开发行前审计 Git 历史；仅删除当前文件不能消除历史中的内网域名和旧配置。

## 13. 测试价值与验证矩阵

这些行为保护隐私边界、公开产物契约和外部上传协议，能够形成稳定可观察断言，通过自动化测试价值门禁。

| 层级 | 现有或拟新增 owner | 场景 | 修改前失败证据 | 修改后结果 |
|---|---|---|---|---|
| L1 | `IssueReportBundleBuilderTest` | 原始模型包含签名密码、路径、placeholder 和二进制依赖 | 当前目录打包可将其加入 zip | zip 和 manifest 中均不存在禁入内容 |
| L1 | `IssueReportBundleBuilderTest` | manifest 与实际 zip entry 比较 | 当前无 manifest | entry、大小、选择状态一致 |
| L1 | `IssueReportUploaderTest` | 上传到用户指定 URL | 当前可能 fallback 多个服务器 | 本次只请求一个确认 URL |
| L1 | `IssueReportUploaderTest` | HTTP、user-info、fragment 和非法 URL | 当前缺少独立校验 | 非法目标在发送前被拒绝 |
| 静态产物守卫 | `PublicDistributionContractTest` 或构建校验任务 | 构建 public 插件包 | 当前产物含真实服务器地址 | 插件 zip/JAR 不含已知后台地址和内网域名 |
| L2 | Report Issues flow owner | URL 或文件选择变化 | 当前没有文件级确认 | 必须重新确认并生成新 manifest |
| 手工矩阵 | public/internal 插件包 | 全新安装后启动、编译、部署 | 当前可能自动选服和检查更新 | public 无 Jugg 网络请求，internal 使用注入配置 |

现有测试治理：

- `JuggServerTest#testReport` 没有可判定断言，实施时不应继续作为网络行为 owner。
- `TaskRunnerManagerTest#failed task is reported` 保护当前自动上报行为；外网包禁用自动上报后，应把稳定契约上移到发行配置或 reporter 边界，而不是继续在任务编排层验证 mock 调用。
- 禁止为测试在生产代码加入仅服务于 mock 的 lambda、provider 或 supplier。

定向验证建议：

```text
./gradlew :main:test --tests '*IssueReportBundleBuilderTest' --tests '*IssueReportUploaderTest'
./gradlew :idea:test --tests '*ReportIssue*Test' --tests '*PublicDistributionContractTest'
./gradlew :idea:compileKotlin
./gradlew :idea:buildPlugin
```

最终还需要对 public/internal 两个真实插件产物执行网络观察和内容扫描，编译成功不能替代该证据。

## 14. 实施顺序

### 阶段一：先建立失败证据和发行边界

1. 构建当前插件包，证明产物中存在服务器地址。
2. 构造包含敏感 `SigningConfig` 的项目模型，证明当前原始目录打包缺少安全边界。
3. 增加 public/internal 构建配置和公开产物守卫。
4. public 装配阶段停止启动后台更新、上报、热更新和配置下发。

### 阶段二：建立安全诊断包

1. 新增 `IssueReportBundleBuilderTest` 失败用例。
2. 实现安全 DTO、白名单文件、文本脱敏和 manifest。
3. 移除原始 `project_infos`、remote diff、临时 Gradle 项目信息的上传入口。
4. 验证 manifest 与 zip entry 一致。

### 阶段三：独立上传 URL 与交互

1. 新增 uploader 协议测试，证明单一目标和 URL 校验。
2. 新增 `reportUploadUrl`，与 `serverUrl` 解耦。
3. 更新确认窗口，展示完整 URL 和实际文件清单。
4. 失败时保留 zip，不执行 fallback。

### 阶段四：产物与发布验证

1. 构建 public/internal 两个插件包。
2. 扫描 public 产物中的域名、URL 和内部配置。
3. 验证 public 全新安装后无 Jugg 自动网络请求。
4. 验证 internal 使用 CI 注入配置。
5. 验证用户主动上传的清单、zip、请求目标和 Report ID。

## 15. 文档同步

实现时至少同步：

- `docs/ai_knowledge/05_utilities.md`：远端服务职责、发行配置和上传边界。
- `docs/ai_knowledge/98_code_map.md`：新增 bundle builder、uploader 和发行配置入口。
- `docs/wiki/zh/guide/report-issue.md`：上传 URL、文件清单、脱敏和本地保存。
- `docs/wiki/guide/jugg-backend/diagnostics.md` 及中文对应页：事件上报与主动问题报告的新边界。
- `docs/wiki/zh/guide/jugg-backend/index.md` 及英文对应页：外网包无预置后台，内网包构建注入。
- README：公开发行版默认离线和隐私说明入口。

## 16. 剩余待决策事项

以下事项不阻塞方案成立，但实现前需要确认默认值：

- internal 构建的任务名和 CI 注入方式。
- 内网 HTTP 是否需要临时兼容，以及警告文案。
- 默认选取最近几份编译日志和单包大小上限。
- 服务端成功响应中的 Report ID 字段协议。
- 外网包是否允许持久化上次成功上传 URL。
- 诊断包默认本地保留时间和清理策略。

## 17. 残余风险

- 文本日志无法仅靠规则证明绝对不包含未知业务秘密，因此用户预览仍是必要的最后一道防线。
- 内网环境并不等于无敏感数据风险，禁入字段必须对两种发行包统一生效。
- 公开源码中的匿名上传入口天然可能被滥用；服务端仍需要大小限制、速率限制和保留期。
- 如果公开仓库保留完整历史，旧服务器地址、内部提交信息和历史二进制仍可能存在，需要单独执行历史审计。
- jar 热更新和远程自定义编译器属于远程代码执行供应链问题，不应因为本方案完成而视为已解决。
