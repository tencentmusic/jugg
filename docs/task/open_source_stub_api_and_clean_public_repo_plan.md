# Android Studio Stub API 与干净公开仓库方案

> 状态：Stub API 与适配 workflow 已实现；干净公开仓库尚未实施
> 关联事项：P0-01 第三方二进制与知识产权归属、P0-06 Git 历史与内部信息
> 基线：Jugg `3.1.5-release` 与 2026-07-31 当前工作树
> 一致性规则：文档与代码冲突时，以代码和实际发布产物为准。

> 范围说明：本文是工程替换和公开仓库治理方案，不是《附件1.开源软件信息表》的填表方案。附件明确定义开源 API 也属于“使用”，因此替换为 Stub API 后仍需判断并登记对应上游软件、版本、协议和修改状态。

## 1. 决策摘要

本方案确认两个方向：

1. `deploy_compat/*/libs` 中的 Android Studio / Android Plugin 完整 JAR 不再保留，改为只包含编译所需 ABI 的、按 Android Studio 版本隔离的源码 Stub API。
2. 内部仓库保留现有完整 Git 历史；公开仓库从审核后的干净源码快照新建，不继承内部仓库的 commit、tag、branch 或 Git object。

最终公开仓库不通过改写内部仓库历史获得，而是创建一个没有内部父提交的新仓库。这样既不破坏内部开发历史，又能确保公开 Git 对象库中从未出现过待清理的二进制、凭据和内部信息。

## 2. 当前事实

- `deploy_compat/*/libs` 当前跟踪 42 个 Android Studio / Android Plugin JAR，归并为 9 类文件名。
- 各 `deploy_compat/v_*` 模块仅通过 `compileOnly fileTree(...)` 使用本地 JAR，插件运行时使用用户安装的 Android Studio 真实实现。
- `platform_compat/base_api` 已使用源码 stub 支撑 `main` 脱离 IDE 编译，说明 Stub API 模式在当前工程中已有先例。
- `deploy_compat` 现有 26 个生产源码文件直接 import 约 77 个 Android / IntelliJ 外部类型。
- 编译产物的外部类型引用并不小：Chipmunk compat 约 112 个，Quail compat 约 81 个；纯手写且无校验的 stub 容易产生签名漂移。
- Quail 已迁移到 `com.android.tools.deployer.common` / `install`，并且不继承 legacy compat，因此适合作为独立 POC。
- 当前仓库历史还包含内网地址、个人路径、公司邮箱、大型二进制和其他内部信息，仅删除当前树中的 JAR 不能形成干净公开历史。

## 3. 目标与非目标

### 3.1 目标

- 删除 `deploy_compat/*/libs` 中完整 Android Studio / Android Plugin JAR。
- 公开仓库只保留兼容层编译实际需要的类型和成员声明。
- Stub API 保持与目标 Android Studio 版本的 JVM ABI 一致。
- Stub API 只参与编译，绝不进入插件 zip 或运行时 classpath。
- 公开仓库可以在不读取内部 Git 历史的情况下独立创建。
- 公开仓库的全部 Git object、branch、tag 和 release 均不包含内部历史文件。
- 保留公开版本需要的第三方许可证、NOTICE、SBOM 和源码来源记录。

### 3.2 非目标

- 首轮不重构部署业务行为、fallback 顺序或用户交互。
- 首轮不同时清理 `IAsDeployerCompat` 仍暴露的所有 Android Studio 类型。
- 不把整个兼容层改成反射实现。
- 不在本方案内处理 `main/libs`、AAPT2 inclink、rsync、sshpass 等其他第三方二进制；它们仍由 P0-01 的其他子任务处理。
- 不改写或销毁内部仓库 Git 历史。
- 不尝试让公开仓库保留内部 commit hash、tag 或 merge 关系。

## 4. Stub API 目标结构

推荐保持版本隔离，不先建立包含所有版本成员的 union stub：

```text
deploy_compat/
├── stub_api/
│   ├── v_chipmunk/
│   ├── v_giraffe/
│   ├── v_hedgehog/
│   ├── v_iguana/
│   ├── v_meerkat/
│   ├── v_narwhal/
│   ├── v_narwhal_feature/
│   ├── v_otter/
│   ├── v_panda/
│   └── v_quail/
├── interface/
├── v_chipmunk/
└── ...
```

每个 compat 模块只对对应 Stub API 建立 `compileOnly` 依赖：

```groovy
dependencies {
    compileOnly project(':deploy_compat:stub_api:v_quail')
    implementation project(':deploy_compat:interface')
}
```

Stub API project 不得成为 `idea`、`main` 或最终插件包的 runtime dependency。

### 4.1 为什么不使用 union stub

如果 union stub 同时声明旧版和新版成员，某版本 compat 可能误用目标 IDE 实际不存在的 API：

```text
错误成员存在于 union stub
  -> Kotlin/Java 编译成功
  -> 目标 Android Studio 中不存在该成员
  -> 运行时 NoSuchMethodError / NoSuchFieldError
```

首轮按版本保留完整 ABI 边界。只有在 ABI 校验证明多个版本声明完全一致后，才允许复用公共源码目录。

## 5. Stub API 内容规则

Stub 只保留 compat 编译和 JVM 链接需要的最小声明：

- 完整包名和类名。
- class / interface / enum / annotation 的准确类型。
- 父类、接口和内部类关系。
- 构造器、方法和字段的准确 JVM descriptor。
- static、instance、abstract、final、synthetic 等影响链接的属性。
- 泛型签名、throws、必要注解和 Kotlin 调用所需元数据。
- 可能被编译器内联的 primitive / String 常量及其准确值。

Stub 方法体不实现业务，只返回安全默认值或抛出 `UnsupportedOperationException`。新增公共 Stub 类必须有英文介绍性注释，说明它只用于编译，不能进入运行时。

Stub API 不会自动摆脱上游许可证。生成和维护时仍需记录来源并保留适用的许可证和 attribution；它解决的是“不再公开完整第三方实现二进制”，把审核范围收敛为少量可读的 ABI 声明，而不是把第三方 API 重新声明为 Jugg 自有版权。

以下形态必须与真实类严格一致：

- 真实 class 不能写成 interface，否则调用指令会变化并触发 `IncompatibleClassChangeError`。
- static 方法不能写成实例方法。
- 构造器参数和返回类型不能使用“近似类型”。
- enum 常量不能用普通静态字段替代。
- Kotlin extension / property 若依赖 `@Metadata` 才能正确解析，应使用对应 Kotlin stub、改成稳定 JVM 调用，或在最小范围内使用反射。

## 6. Stub 生成与维护

### 6.1 生成输入

生成工具接受一个明确的 Android Studio JAR 目录，不自动检测安装位置。原始完整 JAR 只存在于维护者本机、内部缓存或临时工作目录，不提交到公开仓库。

### 6.2 生成流程

```text
目标 Android Studio JAR
  -> 扫描 compat 编译产物真实引用的类和成员
  -> 递归补齐局部变量、泛型、方法签名、字段、继承和内层类引用
  -> 生成最小 Stub API JAR，并保留 Kotlin module metadata
  -> 记录目标 IDE build、原始 JAR 路径、SHA-256 和生成结果
  -> 切回 Stub clean compile 并人工 review 后提交
```

生成工具复用字节码 descriptor，不通过普通反编译复制方法实现。普通方法体被替换为默认返回，公开仓库只保留声明、来源记录和必要许可证，不保留输入 JAR。完全未使用的 import 不会进入编译产物，因此生成后必须重新编译，由维护者删除无效 import 或完成最小适配。

### 6.3 来源记录

每个版本 Stub API 至少记录：

- Android Studio 版本名和完整 build number。
- 输入 JAR 文件名与 SHA-256。
- 生成工具版本或 commit。
- 生成的类和成员数量。
- 需要人工维护的 Kotlin metadata、常量或反射例外。
- 对应许可证和 NOTICE 来源。

## 7. ABI 校验

新增可选的 `verifyStubApi` 验证任务，输入目标 Android Studio 安装目录，将 Stub 与真实类逐项比较：

| 校验项 | 失败条件 |
|---|---|
| class/interface/enum 类型 | Stub 和真实类种类不一致 |
| 继承关系 | 父类或接口 descriptor 不一致 |
| 构造器 | 目标构造器不存在或访问级别不满足调用 |
| 方法 | 名称、参数、返回类型、static 属性不一致 |
| 字段 | 名称、类型、static 属性不一致 |
| 常量 | 会被内联的值不一致 |
| 内部类 | owner、名称或 static 关系不一致 |

`verifyStubApi` 不要求公开 CI 下载十套 Android Studio。公开 CI 负责用已提交 Stub 编译；兼容层维护或升级版本时，由维护者或内部 CI 使用真实 IDE 执行 ABI 校验并保存报告。

## 8. 迁移阶段

### 阶段 0：冻结基线

- 保存当前 `3.1.5-release` 插件 zip 清单和 SHA-256。
- 执行现有 compat 架构守卫、dispatcher 回归和选定 L3 基线。
- 保存 42 个平台 JAR 的路径、SHA-256 和目标版本映射，仅用于内部迁移证据。

### 阶段 1：Quail POC

- 新增 `stub_api/v_quail`。
- 从 Quail 真实 JAR 生成并裁剪最小 Stub API。
- 将 `v_quail` 改为 `compileOnly project(...)`。
- 删除当前树中的 `v_quail/libs` JAR。
- 完成编译、ABI 校验、插件产物扫描和真实 Quail smoke test。

Quail 独立于 legacy 继承链，适合验证生成工具、Kotlin API 和运行时不打包守卫。

### 阶段 2：Chipmunk legacy 基线

- 新增 `stub_api/v_chipmunk`。
- 验证 legacy deployer、Gradle model、run configuration 和 device selection API。
- 删除 `v_chipmunk/libs`。
- 在 Chipmunk 环境验证 install、swap 和兼容 fallback。

### 阶段 3：逐版本迁移

按现有继承链逐个迁移：

```text
Giraffe
  -> Hedgehog
  -> Iguana
  -> Meerkat
  -> Narwhal
  -> Narwhal Feature Drop
  -> Otter
  -> Panda
```

每个版本必须独立完成“stub 生成、ABI 校验、删除 JAR、定向编译、产物扫描”，禁止一次删除全部 JAR 后集中修复。

### 阶段 4：仓库边界守卫

- 删除 `deploy_compat/*/libs` 和 `flatDir` / `fileTree` 依赖。
- 架构守卫禁止重新提交 Android Studio 平台 JAR。
- 构建产物守卫禁止 Stub class 进入插件 zip。
- 同步 `04_engineering_compat.md`、`98_code_map.md`、README 和第三方清单。

## 9. 干净公开仓库

### 9.1 仓库关系

```text
内部仓库
  保留完整历史、内部配置和迁移证据
        |
        | 从审核后的 tracked files 生成干净快照
        v
全新公开仓库
  新 root commit，无内部 parent、tag、branch 或 Git object
```

公开仓库不能通过复制内部 `.git`、`git clone` 后删文件或普通 `git rm` 建立。推荐从允许公开的文件列表导出到全新空目录，再初始化新的 Git 仓库。

### 9.2 导出规则

- 使用明确 allowlist 或审核后的 tracked file 清单导出源码。
- 不复制 `.git`、`.idea`、本机构建缓存、诊断包和内部配置。
- 不复制内部 remote、hook、tag、release metadata 或 CI 凭据。
- 不把旧 commit hash 写入公开 tag、release 或 source map。
- 公开仓库以一个新的 root commit 开始，之后再正常保留公开开发历史。
- 如需保留贡献信息，通过 `CONTRIBUTORS`、CHANGELOG 或版权声明表达，不复制内部作者邮箱历史。

### 9.3 公开仓库扫描

创建公开 root commit 前和创建后都执行：

- 凭据、token、私钥和密码扫描。
- 内网域名、公司邮箱、个人绝对路径和内部项目名扫描。
- JAR、AAR、native executable、大文件和压缩包扫描。
- `git rev-list --objects --all` 全对象检查。
- `git fsck --full` 完整性检查。
- branch、tag 和 release 列表检查。
- LICENSE、NOTICE、SBOM 与发布包内容对照。

完成标准不是“当前分支看不到旧文件”，而是公开仓库的 Git object 中从未存在旧文件。

## 10. 测试与验证矩阵

| 层级 | Owner / 验证 | 场景 | 预期结果 |
|---|---|---|---|
| 静态架构守卫 | `DeployCompatArchitectureTest` | 扫描平台 JAR、runtime stub 依赖和禁止边界 | 不允许新增 `deploy_compat/*/libs/*.jar`，不允许 Stub 成为 runtime dependency |
| 构建验证 | 各 `deploy_compat:v_*:compileKotlin` | 只使用对应 Stub 编译 | 所有版本模块定向编译成功 |
| 字节码/API | `verifyStubApi` | Stub 对比真实目标 IDE | 类、字段、方法和继承 descriptor 全部匹配 |
| 产物验证 | `:idea:buildPlugin` zip 扫描 | 检查 Stub class 和平台 JAR | 最终 zip 不包含任何 Stub class 或待移除平台 JAR |
| L2 | `AsDeployerCompatDispatcherTest` | API 兼容异常 fallback | 兼容分发行为保持不变 |
| 静态契约 | `IdeModuleInfoCompatContractTest` | 各版本 module info 契约 | 现有字段契约保持不变 |
| L3 / 手工矩阵 | Chipmunk、中间版本、Quail | install、code swap、full swap、设备选择、debug attach | 代表版本用户主链路与迁移前一致 |
| Git 产物 | 公开仓库 object scan | 全分支和 tag | 不存在内部历史 blob、凭据和待移除二进制 |

不为 Stub 方法体新增运行时单元测试。Stub 的稳定契约是 ABI、模块依赖和最终产物边界，应由编译、字节码和架构守卫验证。

## 11. 回退策略

- 每次只迁移一个版本，失败时恢复该版本内部 JAR 依赖，不影响其他版本。
- Stub ABI 校验失败时只修正对应类或成员，不扩大到业务重构。
- 真实 IDE smoke test 失败时保留失败版本的旧内部构建方式，继续调查签名或 Kotlin metadata 差异。
- 公开仓库发布失败不改动内部仓库历史，只丢弃未发布的公开仓库并重新导出。

## 12. 与开源发布治理的关系（不属于附件 1）

```text
P0-01 Stub API / 第三方二进制治理 ─┐
P0-06 干净公开仓库                 ├─> P0-08 外网发布门禁
P0-02 public/internal 发行模式 ─────┤
P0-03 诊断包安全 ──────────────────┤
P0-04 MCP 安全 ────────────────────┤
P0-05 SSH 凭据 ────────────────────┤
P0-07 远程代码供应链 ───────────────┘
```

Stub API 和干净公开仓库解决“哪些文件可以公开”；其他 P0 解决“公开产物运行时是否安全”。所有 P0 完成后才进入 P0-08 的最终产物门禁。

## 13. 完成标准

- `deploy_compat` 当前树不再包含 Android Studio / Android Plugin 完整 JAR。
- 所有 compat 模块只依赖版本化 Stub API 和正式公共依赖完成编译。
- Stub API 有来源记录并通过目标 IDE ABI 校验。
- 插件 zip 不包含 Stub class 和待移除平台 JAR。
- 代表 Android Studio 版本兼容链路验证通过。
- 全新公开仓库从新的 root commit 开始。
- 公开仓库全部 Git object 不包含内部历史、待移除二进制、凭据或内部地址。
- 公开仓库包含经过核对的 LICENSE、第三方 NOTICE、SBOM 和来源清单。
- 内部仓库保持可用，现有内部 commit、branch 和 tag 不因公开发布被重写。

## 14. 开源发布后续事项（不属于附件 1 填表）

### 下一专题：P0-04 MCP 安全 + P0-05 SSH 凭据

这两个问题应合并讨论，因为当前风险链是：

```text
MCP 自动启动且缺少鉴权
  -> 高权限工具可被本机或局域网调用
  -> SSH 信息模型返回明文密码
  -> 完整 MCP 响应写入日志
  -> 凭据可能继续进入诊断包
```

需要共同确定 MCP 是否默认关闭、loopback 绑定、会话 token、Origin 策略、高风险操作确认、PasswordSafe 数据模型及日志摘要边界。

### 第二专题：P0-02 发行模式 + P0-07 远程代码供应链

`public | internal` 构建模式是禁用后台、jar 热更新和远程自定义编译器的共同基础。P0-02 已有设计，下一步应把 P0-07 的能力矩阵和构建失败门禁并入同一实现计划，避免在运行时散落 public 判断。

### 第三专题：P0-03 诊断包安全

在发行模式和凭据模型确定后，实现安全 DTO、白名单、manifest、唯一上传目标和脱敏规则，确保诊断包不会重新带出已清理的内部信息或凭据。

### 最终门禁：P0-08

所有其他 P0 完成后，统一验证公开仓库与插件产物：

- 无内部域名和默认后台地址。
- 无凭据、私钥和个人路径。
- 无未批准第三方二进制或 Stub class。
- public 包无自动后台与远程代码入口。
- LICENSE、NOTICE、SBOM 与产物逐项对应。
- 干净环境可以安装并完成基本启动、编译和部署 smoke test。
