# Jugg 开源就绪清单

> 状态：持续维护  
> 最后核对：2026-07-30  
> 目标：在公开源码和发布外网插件包前，按 P0/P1/P2 管理开源风险、完成标准和验证证据。  
> 一致性规则：文档与代码冲突时，以代码为准。

> 范围说明：本清单是“开源发布就绪”治理清单，范围显著大于《附件1.开源软件信息表》，不得把这里的 P0 等同于附件填表 P0。附件填表只核对软件名称、版本、协议、Copyright、协议链接、下载链接、是否修改和备注，见 [jugg_open_source_software_inventory.md](../2026-08/jugg_open_source_software_inventory.md) §5。

## 1. 优先级定义

| 优先级 | 定义 | 发布门禁 |
|---|---|---|
| P0 | 可能造成版权/合规阻断、敏感信息泄露、远程代码执行、凭据泄露或公开产物错误联网 | 未完成不得公开仓库或发布外网包 |
| P1 | 影响外部贡献、可复现构建、发布可信度、依赖可用性和维护效率 | 建议首个公开版本前完成，最迟在首个稳定版本前完成 |
| P2 | 长期治理、体验、生态和维护效率提升 | 不阻塞首次开源，进入公开路线图持续治理 |

状态约定：

- `未开始`：只有问题记录，尚未形成可执行方案。
- `方案已确认`：已形成方案文档，但未获得生产实现授权或尚未实施。
- `进行中`：已获得实现授权并开始执行。
- `已完成`：完成实现、验证、文档同步和提交。
- `需外部确认`：依赖公司、法务、版权方或服务提供方结论。

## 2. 总览

| ID | 优先级 | 主题 | 当前状态 | 主要 owner/入口 |
|---|---|---|---|---|
| P0-01 | P0 | 第三方二进制与知识产权归属 | 方案已确认 | `open_source_stub_api_and_clean_public_repo_plan.md`、`main/libs`、预编译工具 |
| P0-02 | P0 | 外网/内网发行包与后台网络隔离 | 方案已确认 | `open_source_network_and_diagnostics_design.md` |
| P0-03 | P0 | 诊断包白名单、脱敏和明确上传目标 | 方案已确认 | `JuggServer.reportAndUploadLogs` |
| P0-04 | P0 | MCP 本地服务监听、鉴权与敏感日志 | 未开始 | `McpLocalServer`、`JuggInitializer` |
| P0-05 | P0 | SSH 密码存储与传播 | 未开始 | `JuggRunConfigurationOptions`、MCP SSH 信息 |
| P0-06 | P0 | Git 历史、秘密和内部信息审计 | 方案已确认 | `open_source_stub_api_and_clean_public_repo_plan.md`、公开仓库导出与全对象扫描 |
| P0-07 | P0 | 远程代码下发与公开发布供应链 | 未开始 | 热更新、自定义编译器、插件发布 |
| P0-08 | P0 | 外网产物发布门禁 | 未开始 | `:idea:buildPlugin` 产物验证 |
| P1-01 | P1 | 仓库体积与大二进制治理 | 未开始 | Git 历史、JAR、native executable |
| P1-02 | P1 | 干净环境可复现构建 | 未开始 | Gradle、Android Studio/JDK/toolchain |
| P1-03 | P1 | 公共 CI 与自动审计 | 未开始 | CI workflow、定向测试、扫描任务 |
| P1-04 | P1 | 依赖仓库与公共网络可用性 | 未开始 | Gradle repositories、Wiki lockfile |
| P1-05 | P1 | 第三方 NOTICE、SBOM 与依赖清单 | 未开始 | 发布产物、依赖报告 |
| P1-06 | P1 | 公开文档与内网文档分离 | 未开始 | README、Wiki、`docs/task`、skills |
| P1-07 | P1 | 社区治理与安全报告入口 | 未开始 | `SECURITY.md`、`CONTRIBUTING.md` 等 |
| P1-08 | P1 | 兼容矩阵、安装和支持边界 | 未开始 | README、Wiki、Marketplace 页面 |
| P1-09 | P1 | 发布流程、签名、校验和与回滚 | 未开始 | Release CI、Marketplace/GitHub Release |
| P2-01 | P2 | 默认关闭的匿名遥测评估 | 未开始 | 公开发行版产品决策 |
| P2-02 | P2 | 贡献者协议与提交来源声明 | 未开始 | DCO/CLA 选择 |
| P2-03 | P2 | Roadmap、维护范围和响应预期 | 未开始 | GitHub 项目治理 |
| P2-04 | P2 | 依赖升级自动化与兼容回归 | 未开始 | Renovate/Dependabot、版本矩阵 |
| P2-05 | P2 | 文档国际化与示例去内部化 | 未开始 | Wiki、README、测试/脚本示例 |
| P2-06 | P2 | 长期模块边界与可扩展性治理 | 未开始 | backend、MCP、compat、CLI 架构 |

## 3. P0：公开发布阻断项（非附件 1 填表优先级）

### P0-01 第三方二进制与知识产权归属

**已确认方向**

- `deploy_compat/*/libs` 中的 Android Studio / Android Plugin 完整 JAR 改为按版本隔离的源码 Stub API。
- Stub API 只参与编译，通过真实目标 IDE 做 ABI 校验，且不得进入插件发布包。
- 其他修改版 JAR、native executable 和内嵌第三方组件继续逐项治理。

详细方案见：[open_source_stub_api_and_clean_public_repo_plan.md](../2026-08/open_source_stub_api_and_clean_public_repo_plan.md)。

**当前事实**

- `deploy_compat/*/libs` 中包含多个 Android Studio 版本的 `android.jar`、`sdk-tools.jar` 等文件。
- `deploy_compat/README.md` 指向 Android Studio 安装目录作为这些依赖的来源。
- `main/libs` 包含 R8、修改版 Kotlin Android Extensions、SQLite、ASM 等预编译 JAR。
- 插件资源包含 AAPT2 inclink、rsync、sshpass 等平台二进制。
- 当前仓库只有项目级 MIT License，没有覆盖所有预编译文件的统一第三方归属清单。

**风险**

- 项目 MIT License 不能自动授予第三方文件的再分发权。
- 修改版第三方 JAR 可能需要公开修改源码、补丁、许可证或 NOTICE。
- 公司职务成果、内部测试资产和历史贡献可能需要额外开源授权。

**完成标准**

- 为每个第三方 JAR、AAR 和可执行文件记录来源、版本、许可证、是否修改、是否进入最终插件和再分发依据。
- 获得公司/版权方对 Jugg 代码和相关资产公开发布的书面确认。
- 不能再分发的文件移出公开仓库，改为官方依赖、构建下载或本地提取。
- 修改版文件提供可审计源码或 patch。
- 生成并随发布产物分发第三方 NOTICE。

**验证证据**

- 第三方组件清单。
- 法务或公司开源审批记录。
- 发布包内容与许可证清单逐项对应。

### P0-02 外网/内网发行包与后台网络隔离

**当前事实**

- 当前公共资源包含真实服务器 URL。
- `JuggServer` 初始化会触发服务器选择和连通性判断。
- 更新、事件上报、热更新、远程配置和诊断上传共用同一后台控制面。

**已确认方案**

- 外网包无预置后台、无 Jugg 自动网络行为。
- 内网包由私有 CI 或私有构建配置注入服务器地址。
- 不通过运行时判断内外网自动切换发行模式。

详细设计见：[open_source_network_and_diagnostics_design.md](open_source_network_and_diagnostics_design.md)。

**完成标准**

- public 构建不包含服务器 URL 和内网域名。
- public 启动、编译、部署、CLI 和 MCP 本地能力不触发 Jugg 后台请求。
- internal 构建缺失必要配置时构建失败。
- public 构建发现非空后台地址时构建失败。

### P0-03 诊断包白名单、脱敏和明确上传目标

**当前事实**

- 当前问题报告会直接压缩日志、logcat、项目模型、remote diff、临时 Gradle 项目信息和全局 hook 日志。
- 原始项目模型包含签名密码、路径、Manifest placeholders 和 APT/KAPT 参数。
- 上传失败可能依次尝试多个服务器。

**已确认方案**

- 使用独立的完整 `reportUploadUrl`。
- 上传前展示最终 URL 和实际文件清单。
- 通过安全 DTO、文件白名单、文本脱敏和 manifest 生成诊断包。
- 原始项目模型、签名密码、SSH 凭据、二进制依赖和构建脚本永不进入诊断包。
- 上传失败保留本地 zip，不切换其他服务器。

**完成标准**

- manifest 与最终 zip entry 完全一致。
- 用户看到的文件和实际上传文件一致。
- 禁入字段测试稳定通过。
- 上传请求只发送到用户确认的唯一 URL。

### P0-04 MCP 本地服务监听、鉴权与敏感日志

**当前事实**

- `JuggInitializer` 在项目初始化后自动启动 MCP HTTP server。
- `McpLocalServer` 使用只提供端口的 `InetSocketAddress`，没有显式绑定 loopback 地址。
- 请求没有 bearer token、会话密钥或客户端配对流程。
- CORS 响应反射请求 Origin。
- MCP 提供编译、部署、重装、设备操作、截图、点击和 SSH 信息等高权限工具。
- MCP 完整响应会写 debug 日志，SSH 信息响应中当前包含密码。

**风险**

- 本机恶意网页、局域网客户端或其他进程可能调用高权限 IDE/设备能力。
- SSH 密码可能进入 MCP 响应和 Jugg 日志，随后又进入诊断包。

**推荐方向**

- 只绑定 IPv4/IPv6 loopback。
- MCP 默认关闭，由用户明确启用。
- 每次 IDE 会话生成随机访问 token，并要求所有请求携带。
- 拒绝浏览器 Origin 或使用严格固定白名单，不反射任意 Origin。
- 高风险工具继续要求 IDE 侧用户确认。
- 请求和响应日志只记录方法名、结果码和安全摘要。
- SSH 密码永不进入 MCP 数据模型和日志。

**完成标准**

- 非 loopback 客户端无法连接。
- 无 token、错误 token、浏览器跨域请求均被拒绝。
- 高风险工具无法绕过用户确认。
- 日志中不存在请求正文、响应正文和凭据。

### P0-05 SSH 密码存储与传播

**当前事实**

- `remoteSshPassword` 作为普通字符串保存在 `RunConfigurationOptions`。
- 后台模板、远端机器返回模型和 MCP SSH 信息模型都允许携带明文密码。

**风险**

- 密码可能进入 `.idea` 配置、workspace、导出配置、日志和诊断包。

**推荐方向**

- 优先使用 SSH key。
- 密码使用 IntelliJ `PasswordSafe` 或仅保留在当前会话。
- Run Configuration 只保存凭据引用，不保存明文值。
- 后台模板不得下发 SSH 密码。
- MCP 不返回密码，只允许在 IDE 内确认后发起受控操作。

**完成标准**

- Git 工作树、IDE 配置文件、日志和诊断包中均不存在 SSH 明文密码。
- 删除配置或项目后能够清理对应 PasswordSafe 条目。

### P0-06 Git 历史、秘密和内部信息审计

**已确认方向**

- 内部仓库保留完整历史，不执行破坏性的全仓历史改写。
- 公开仓库从审核后的干净源码快照新建，以新的 root commit 开始，不继承内部 commit、tag、branch 或 Git object。
- 公开前后都对完整 Git object、凭据、内网信息和二进制执行扫描。

详细方案见：[open_source_stub_api_and_clean_public_repo_plan.md](../2026-08/open_source_stub_api_and_clean_public_repo_plan.md)。

**当前事实**

- 当前和历史提交中存在内网服务器地址。
- 历史作者信息包含公司域名邮箱。
- 文档、脚本和测试中存在个人绝对路径、内部项目名称和构建 variant。
- 仓库仍跟踪部分 `.DS_Store`。

**推荐方向**

- 对完整 Git 历史执行 secret、域名、邮箱、绝对路径和大文件扫描。
- 逐项区分真实秘密、个人信息、公司内部标识和允许公开的普通 fixture。
- 决定清洗历史，或建立经过审计的全新公开仓库。
- 保留作者历史时，确认作者邮箱公开意愿和著作权归属。

**完成标准**

- 历史扫描报告无未处理 P0 项。
- 当前树和公开历史不包含真实凭据、私钥和内部服务器地址。
- 个人路径和内部项目示例已替换为通用内容，或有明确保留理由。

### P0-07 远程代码下发与公开发布供应链

**当前事实**

- 后台可下发并自动安装插件 jar 热更新。
- 后台项目配置可下发并加载自定义编译器 jar。
- 当前 jar 校验主要使用 MD5，一致性校验不能证明来源可信。

**推荐方向**

- public 构建禁用 jar 热更新和远程自定义编译器下发。
- public 发布只走 JetBrains Marketplace 或公开 Release。
- internal 如需保留远程代码能力，使用 HTTPS、非对称签名、固定信任根、回滚和审计记录。

**完成标准**

- public 产物没有后台远程代码安装入口。
- release 产物有 SHA-256 和签名/来源证明。
- internal 远程代码校验不再依赖服务端同时下发内容和 MD5。

### P0-08 外网产物发布门禁

**目标**

在发布 public 插件前自动证明：

- 不包含内网域名、后台默认地址和内部下载地址。
- 不包含明文凭据、私钥和未批准的本地路径。
- 不包含禁止再分发的第三方二进制。
- public 后台和远程代码功能处于禁用状态。
- 插件可以在干净环境安装并完成基本启动。

**验证证据**

- 插件 zip/JAR 内容扫描。
- 网络观察矩阵。
- 插件安装 smoke test。
- 许可证和第三方组件对照报告。

## 4. P1：首个稳定公开版本前完成

### P1-01 仓库体积与大二进制治理

**当前事实**

- `.git` 体积约 818 MiB。
- 历史中存在超过 90 MiB 的单个 blob。
- 多版本 Android Studio JAR、R8、Bundletool、native executable 和大型测试 fixture 被直接提交。

**完成标准**

- 移除无再分发必要的大二进制。
- 可公开依赖优先使用官方 Maven/下载来源。
- 必须保留的大 fixture 使用 Git LFS、压缩生成物或最小化样本。
- 新 clone 的仓库体积和首次构建时间进入可接受范围。

### P1-02 干净环境可复现构建

**当前缺口**

README 只提供基础 Gradle 命令，没有完整描述 JDK、Android Studio、native toolchain 和各兼容模块依赖来源。

**完成标准**

- 文档明确 JDK、Gradle、Android Studio/IntelliJ、CMake/NDK 和系统依赖版本。
- public/internal 构建命令明确且相互隔离。
- 在无个人缓存、无内网依赖、无本地 Android Studio 复制步骤的干净环境构建 public 产物。
- 构建结果可由 CI 重复生成。

### P1-03 公共 CI 与自动审计

**建议任务**

- 定向编译和选定 L1/L2/L3 回归。
- Wiki build。
- public 插件产物构建和安装 smoke test。
- secret scanning。
- dependency/license scanning。
- public 产物 URL/域名扫描。
- 二进制大小变化检查。
- SBOM 生成。

**完成标准**

- 外部贡献者 PR 能获得稳定、可解释的自动验证结果。
- CI 不依赖内网服务或私有缓存才能通过 public 构建。

### P1-04 依赖仓库与公共网络可用性

**当前事实**

- Wiki `package-lock.json` 固定使用腾讯 npm 镜像。
- 部分模块使用 `flatDir` 和本地 JAR。
- demo 仍包含 `jcenter()`。

**完成标准**

- public 构建只依赖公开、稳定、文档化的依赖来源。
- lockfile 使用公开 registry，或文档明确可替换镜像。
- 删除失效仓库和不必要的 `flatDir`。
- 对下载依赖启用 checksum/dependency verification。

### P1-05 第三方 NOTICE、SBOM 与依赖清单

**完成标准**

- 根目录提供 `THIRD_PARTY_NOTICES` 或等价文件。
- 每个发布产物生成 SBOM。
- 发布包内的第三方组件、源码仓库和许可证能够追溯。
- 依赖升级时自动发现许可证变化。

### P1-06 公开文档与内网文档分离

**当前事实**

- Wiki 仍包含团队下载页、内网后台、远端机器申请等导向。
- `docs/task`、`docs/superpowers`、benchmark 和脚本中存在个人路径和内部项目示例。

**推荐方向**

- 公开用户文档只描述 public 能力和明确标记的 self-hosting 能力。
- 内网发行说明从公开 Wiki 导航中隔离，或由私有文档构建覆盖。
- 内部项目名、个人路径和公司特有环境改成通用示例。

### P1-07 社区治理与安全报告入口

建议增加：

- `SECURITY.md`。
- `CONTRIBUTING.md`。
- `CODE_OF_CONDUCT.md`。
- Issue templates。
- Pull request template。
- Support policy。
- 漏洞私密报告渠道。

`SECURITY.md` 应明确禁止把凭据、私有诊断包和安全漏洞细节直接提交到公开 Issue。

### P1-08 兼容矩阵、安装和支持边界

**完成标准**

- README/官网明确支持的 Android Studio 版本、JDK、AGP、Kotlin 和系统范围。
- 区分“正式支持”“兼容性尝试”“实验性”“内网专属”。
- 明确增量编译不等价于完整 Gradle pipeline 的能力边界。
- 提供安装、卸载、缓存目录和隐私行为说明。

### P1-09 发布流程、签名、校验和与回滚

**完成标准**

- Release 只由受保护 CI 生成。
- 发布 tag、版本、change log 和插件产物一致。
- 提供 SHA-256、签名/来源证明和 SBOM。
- Marketplace 与 GitHub Release 的产物来源关系明确。
- 出现供应链或严重缺陷时有撤回、回滚和安全公告流程。

## 5. P2：开源后的持续治理

### P2-01 默认关闭的匿名遥测评估

- 首个 public 版本不自动上报。
- 只有维护数据明确不足时，再评估默认关闭、用户主动 opt-in 的最小匿名事件。
- 事件只允许结构化错误码和粗粒度环境，不使用用户名、项目名、包名和原始异常。

### P2-02 贡献者协议与提交来源声明

- 根据公司和项目治理需要选择 DCO、CLA 或普通贡献声明。
- 明确贡献者保证有权提交代码和测试资产。
- 该选择不能替代 P0-01 的历史代码权属确认。

### P2-03 Roadmap、维护范围和响应预期

- 公布 roadmap、支持版本和非目标。
- 定义 Issue 分类、响应预期和关闭规则。
- 明确维护者数量、bus factor 和无人维护模块的处理方式。

### P2-04 依赖升级自动化与兼容回归

- 引入 Renovate、Dependabot 或等价机制。
- 升级 Kotlin、Gradle、AGP、Android Studio 和 native 工具时触发兼容矩阵回归。
- 高风险依赖升级必须保留发布前人工审核。

### P2-05 文档国际化与示例去内部化

- 保持中英文核心用户文档同步。
- 示例使用通用工程名、路径和设备信息。
- 自动检查失效链接、过期版本和只存在单语言的关键页面。

### P2-06 长期模块边界与可扩展性治理

- 继续拆分后台更新、事件上报、问题上传和远程配置职责。
- MCP 工具按权限和副作用分组，建立稳定授权模型。
- 兼容层减少对预编译 Android Studio JAR 的仓库存储依赖。
- CLI、IDE、MCP 共用协议时保持单一来源和产物一致性检查。

## 6. 推荐执行顺序

```text
P0-01 Stub API 与其他第三方二进制治理 + P0-06 干净公开仓库
  -> P0-04 MCP 安全 + P0-05 SSH 凭据
  -> P0-02 public/internal 发行模式 + P0-07 远程代码供应链
  -> P0-03 诊断包白名单、脱敏和唯一上传目标
  -> P0-08 public 产物发布门禁
  -> P1 可复现构建、CI、NOTICE、公开文档和社区治理
  -> P2 长期生态和架构治理
```

P0-01 与 P0-06 先确定公开文件和历史边界；P0-04/P0-05 优先收口当前高权限本地服务与明文凭据风险；P0-02 为 P0-07 的 public 能力禁用提供构建期基础；P0-08 作为所有 P0 的统一发布门禁。

## 7. 当前验证证据

本轮只读审计已确认：

- public 资源中存在真实服务器 URL。
- MCP server 自动启动、未显式绑定 loopback、无请求 token，并记录完整响应。
- MCP SSH 信息数据包含密码。
- SSH 密码作为普通 Run Configuration 字符串字段。
- 仓库包含大量 Android Studio JAR、修改版第三方 JAR 和 native executable。
- `.git` 约 818 MiB，历史存在超过 90 MiB 的 blob。
- Wiki lockfile 使用腾讯 npm 镜像。
- 文档和脚本存在个人绝对路径及内部项目示例。

上述结论用于确定优先级，不等价于法律结论。第三方许可证、职务成果和再分发权必须由版权方、公司开源流程或专业法律意见确认。
