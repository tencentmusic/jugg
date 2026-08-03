# KMP Compose 资源 UI Demo 扩展方案

## 背景

现有 Demo 已展示字符串、drawable 和 font 文件指纹，但 array、plural、raw file 和 Android customDirectory 资源仍只能通过编译测试或日志确认。

工作区中的 `test1`、`test3.png`、baseline 图片修改及相关 accessor 属于临时测试改动，本方案不依赖、不修改、不提交这些内容。

## 目标

- Kotlin 2.1、2.3 和 2.3-AGP9 profile 展示 typed array、plural、raw file 和 Android customDirectory string。
- Kotlin 1.9 profile 保持可编译，并明确提示扩展资源需要现代 Compose profile。
- 不使用反射，不解析 Compose 内部 CVR 格式。

## 实现

- 公共 Android UI 调用 `ExtendedComposeResourceSection()`。
- Kotlin 1.9 build file 将 `src/legacyAndroidMain/kotlin` 加入 `androidMain`，提供提示实现。
- Kotlin 2.1、2.3 和 2.3-AGP9 build file 将 `src/modernAndroidMain/kotlin` 加入 `androidMain`，提供 typed resource 实现。
- modern 实现展示：
  - `baseline_engines` string array
  - `baseline_turns` 的 one/other 格式化结果
  - `baseline_payload.txt` 内容、字节数和 hash
  - `custom_android_title` Android customDirectory string

## 变更范围

- 修改 `android_demo_project/kmpCompose/src/androidMain/kotlin/com/sickworm/jugg/demo/kmp/KmpComposeResourceDemo.kt`
- 修改 active KMP build file 及 Kotlin 1.9、2.1、2.3、2.3-AGP9 profile 模板
- 新增 legacy 和 modern 两个 profile-specific UI 实现文件

不修改 App、MainActivity、Manifest、现有资源文件或编译器代码。

## 验证

- 当前工作区使用 Kotlin 1.9 执行 `:app:assembleDebug`。
- 提交后在干净 worktree 中分别执行 Kotlin 1.9 和 Kotlin 2.3 `:app:assembleDebug`。
- 检查 Kotlin 2.3 APK 中 array、plural、raw file 和 Android customDirectory 资源产物。
- 不新增自动化测试；现有 `KmpComposeFlowReproTest` 和 `KmpComposeDeployFlowTest` 继续作为编译与部署 owner。

## 提交边界

仅提交本方案列出的扩展 UI 和 profile source 配置。所有用户临时资源修改保持原暂存/未暂存状态。
