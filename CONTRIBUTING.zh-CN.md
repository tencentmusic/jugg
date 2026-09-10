<p align="left">
  <a href="./CONTRIBUTING.md">English</a> | <strong>简体中文</strong>
</p>

# 参与贡献 Jugg

感谢你帮助改进 Jugg。本指南面向向 [tencentmusic/jugg](https://github.com/tencentmusic/jugg) 提交 Issue、功能建议或 Pull Request 的贡献者。

在 GitHub 上创建 Issue 或 Pull Request 时，页面也会给出本文件的入口。

## 可以怎么参与

- 带 Jugg Report ID 或其他诊断材料反馈缺陷。
- 提出用户可感知的能力或工作流改进。
- 修复缺陷、提交小功能，或改进文档。
- 在 [微信交流群](./docs/images/wechat-group.jpg) 帮助其他用户。

提交新 Issue 前，请先搜索 [现有 Issue](https://github.com/tencentmusic/jugg/issues)。

## 反馈缺陷

请使用 [Bug 反馈](https://github.com/tencentmusic/jugg/issues/new?template=01_bug_report_zh.yml) 模板。

1. 说明非预期行为、期望结果，以及最短复现步骤。
2. 按优先级提供你能给出的最高优先级诊断材料：
   - **Jugg Report ID**（最推荐）
   - Diagnostics Bundle ZIP
   - 可复现的最小 Demo
   - 仅当前三项都不可用时，再提供手工环境信息
3. 提交前移除密钥、签名凭据、私有源码，以及其他不希望公开的内容。

获取 Report ID 或 Diagnostics Bundle：

1. 在 Android Studio 中双击 Shift，搜索并选择 **Report Jugg Issue**；或打开 Jugg Running Panel，点击 **Report Issue**。
2. 选择 **Upload logs**，然后复制 Report ID。无法上传时，勾选 **Save locally without uploading**，再导出 Diagnostics Bundle。

上传内容和本地日志位置见 Wiki [报告问题](https://tencentmusic.github.io/jugg/zh/guide/report-issue)。

不要在公开 Issue 中张贴凭据、私有诊断包或疑似安全漏洞细节。如果认为发现了安全问题，请私下联系维护者，不要提交公开缺陷。

## 提出功能建议

请使用 [功能建议](https://github.com/tencentmusic/jugg/issues/new?template=02_feature_request_zh.yml) 模板。

描述真实使用场景和期望的用户可观察结果即可，不必设计内部实现。

## 开发环境

### 前置条件

- JDK 17（CI 使用 Temurin 17；插件编译目标为 Java 11）
- Git
- 使用仓库自带的 Gradle Wrapper（`gradlew`）；除非你在改 Wrapper 本身，否则不要另装一套 Gradle

如果要改 native agent，还需要 CI 使用的 Android SDK、NDK 和 CMake。大多数 Kotlin/Java 插件改动不需要。

### 克隆与构建

```bash
git clone https://github.com/tencentmusic/jugg.git
cd jugg
./gradlew buildPlugin
./gradlew runIde
```

- `buildPlugin` 会把插件 zip 输出到 `idea/build/distributions`。
- `runIde` 会启动一个用于开发调试的 IDE。

只有需要在真实 Android 工程和设备上验证时，才把构建出的插件安装到本机 Android Studio。

## 仓库结构

| 路径 | 职责 |
|---|---|
| `idea/` | IDE 插件、运行配置、UI 与 IDE 测试 |
| `main/` | 增量编译、部署、项目模型、MCP 与核心测试 |
| `deploy_compat/` | Android Studio 版本兼容 |
| `cmd_line/` | 命令行入口 |
| `docs/wiki/` | 用户 Wiki |
| `docs/ai_knowledge/` | 维护者 / AI 架构说明 |

只改行为所属模块。优先用能解决问题的最小补丁。

## 编码约定

- 改动保持小而聚焦。不要把无关重构、清理或额外抽象混进缺陷修复。
- 源码注释使用英文，不要在代码里写中文注释。
- Kotlin 优先使用非空类型；优先提供可选参数，而不是新的重载。
- 接口以 `I` 开头，默认实现去掉 `I` 前缀，例如 `IDeployHistoryManager` 与 `DeployHistoryManager`。
- 日志统一使用 `JuggLogger`，不要使用 `error`。`warn` 用于用户可见的非预期错误，`info` 用于需要展示的关键流程，`debug` 用于开发者排查并写入 log 文件，`trace` 用于默认关闭的高频日志。
- 日志调用过长时，只在消息字符串的 `+` 处换行，续行相对调用缩进 8 个空格，异常参数与最后一段消息放在同一行：

```kotlin
logger.warn("message bla bla bla" +
        "details", exception)
```

## 测试与验证

每次改动都要提供与风险匹配的验证证据。自动化测试只是验证方式之一，不是每个补丁都必须加测试。

- 不要新增只锁定实现细节、简单透传，或没有可观察结果的 mock 交互测试。
- 不要为测试在生产代码中增加仅服务于 mock 的 `provider` / `supplier` / `factory` / `override` lambda。
- 禁止无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。只跑与本次改动相关的测试：

```bash
./gradlew :idea:compileKotlin
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"
```

只验证编译时可用 `./gradlew :idea:compileKotlin`。需要检查插件打包时可用 `./gradlew :idea:buildPlugin`。

如果无法在不绑定私有实现的前提下写出有价值的自动化断言，请在 Pull Request 中说明：复现证据、未加测试的原因，以及实际做了哪些替代验证。

## 提交信息

提交信息使用英文。标题格式：`[prefix] subject`

- `subject` 以小写字母开头，结尾不用句号。
- 按用户可观察结果选择前缀：
  - `[bugfix]`：既有能力出现漏洞、异常行为或与预期不符
  - `[feature]`：新增用户可感知的能力
  - `[optimize]`：原行为正确，但更清晰、更稳或更好用
  - `[refactor]` / `[docs]` / `[test]` / `[other]`：分别用于无行为变化的重构、纯文档、仅测试及其他改动
- 标题优先描述用户场景和可观察结果，不描述内部实现。
- `[bugfix]` 标题通常写成 `[bugfix] fix <problem> when/after/for <scenario>`。
- `[optimize]` 标题通常写成 `[optimize] <improvement> when/for <scenario>`。

标题不够说明原因和实现时，空一行后再写正文。

示例：

```text
[bugfix] fix incremental deploy skipping resource changes after Gradle fallback
[docs] add repository contributor guidelines
```

## Pull Request

1. Fork 仓库，并从 `main` 拉取新分支。
2. 向 `main` 提交 Pull Request。较大改动时，维护者可能会要求改以 `develop` 为目标分支。
3. 保持 PR 聚焦：一个问题、一个修复、一套验证说明。
4. 在描述里写清：
   - 改了什么用户可观察问题或能力
   - 如何验证
   - 关联 Issue（如有）
5. 接受 review 意见。可以用小的后续 commit 继续修改；除非维护者要求，否则不要 force-push。

不要提交密钥、本地 IDE 文件、`build/` 产物，或无关的格式化改动。

## 文档

- **用户 Wiki** 位于 `docs/wiki`。`docs/wiki/zh/` 下的中文页面是内容基准，英文页面需要保持同步。
- **维护者说明** 位于 `docs/ai_knowledge`。改动影响插件内部、编译/部署行为或 AI 任务路由时，请同步更新。
- 不要把内部类名写进面向普通用户的 Wiki，除非该名称本身对用户可见。

## 社区

在 Issue、Pull Request 和微信群中保持尊重。默认对方出于善意，讨论围绕问题本身，不要分享他人的私有日志或工程文件。

## 许可证

Jugg 使用 [MIT License](LICENSE) 开源。提交贡献即表示你同意按相同许可证授权这些改动。
