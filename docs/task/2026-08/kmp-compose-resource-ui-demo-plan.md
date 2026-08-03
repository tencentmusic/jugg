# KMP Compose 资源 UI Demo 实施方案

## 背景

`android_demo_project/kmpCompose` 当前通过 `MainActivity` 后台日志读取 Compose 字符串资源，缺少可直接观察资源增量编译结果的 UI。现有资源 fixture 同时包含字符串、drawable 和 font，但 font 文件是编译测试用 ASCII 数据，不能作为真实字体渲染。

## 目标

- 在 Android Demo 主页面增加 KMP Compose 资源 Demo 入口。
- 在独立页面展示本地化字符串、Android 字符串、密度 drawable 和 font 文件指纹。
- 保持 Kotlin 1.9、2.1、2.3 和 2.3-AGP9 profile 均可编译。
- 保留现有日志探针和未提交的版本切换改动。

## 变更范围

### KMP 模块

- 新增 `android_demo_project/kmpCompose/src/androidMain/kotlin/com/sickworm/jugg/demo/kmp/KmpComposeResourceDemo.kt`，负责 Compose UI 和资源读取。
- 在 active build file 及所有 KMP profile 模板中增加 `compose.foundation` 依赖。

### App 模块

- 新增 `KmpComposeResourceDemoActivity` 承载 Compose 页面。
- 在 `MainActivity.kt` 和 `activity_main.xml` 增加入口按钮。
- 在 App Manifest 注册 Demo Activity。

## 兼容约束

- UI 仅使用 Kotlin 1.9 旧资源生成器与现代资源生成器共有的 string、drawable、font 和 `Res.readBytes` 能力。
- 不在 UI 中直接引用现代版本独有的 array/plural accessor，避免破坏 Kotlin 1.9 profile。
- 不替换现有 font fixture；页面展示字节数和 content hash，用于确认 font overlay 是否更新。

## 验证

- 不新增自动化测试：该页面用于人工观察资源结果，UI 属性测试会绑定展示实现。
- 复用现有 `KmpComposeFlowReproTest` 和 `KmpComposeDeployFlowTest` 作为资源编译链 owner。
- 当前 Jugg 工程状态为 `PROJECT_NOT_INITIALIZED`，使用 `./gradlew :app:assembleDebug` 验证源码、资源生成与 APK 打包。

## 提交范围

仅提交本方案和上述 UI Demo 改动。现有 Gradle/profile 切换修改及原有 `MainActivity.kt` 日志探针不纳入本次提交。
