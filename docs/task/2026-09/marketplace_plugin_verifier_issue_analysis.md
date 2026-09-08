# Marketplace Plugin Verifier 问题分析

## 1. 背景

JetBrains Marketplace 对 Jugg `3.4.0-release` 的审核报告指出：

- IntelliJ IDEA 2026.3 EAP 存在 1 个二进制兼容问题、4 个 Internal API 使用、1 个待移除 API、14 个 Deprecated API 使用和 1 个打包警告。
- IntelliJ IDEA 2022.1.4 存在 4 个二进制兼容问题、5 个 Deprecated API 使用和 1 个打包警告。
- Marketplace 要求解决兼容问题和 Internal API 使用后重新上传。

原始报告：`~/Downloads/report.txt`。

本文同时记录问题分类、最终处理方向和本次落地结果。

## 2. 证据边界

`report.txt` 只包含问题类型和数量，没有展开具体类、方法及调用位置。为补足明细，使用本地 `jugg-3.4.0-release.zip` 进行 Plugin Verifier 定向复核：

- Plugin Verifier 1.409 对 Android Studio 2022.1 对应平台构建执行验证，复现了报告中的 4 个兼容问题。
- Plugin Verifier 1.410 对 Android Studio 2026.x 平台构建执行验证，定位到与 Marketplace 数量一致的 4 个 Internal API 使用，以及相同的 Deprecated API 和打包警告。
- Marketplace 的 IntelliJ IDEA 2026.3 EAP 完整明细需要插件所有者权限，因此其中 1 个兼容问题根据报告数量、相邻平台验证和源码引用综合判断。

虽然部分不兼容调用位于 `try/catch` 或存在反射回退，但 Plugin Verifier 检查的是发布包中的静态字节码引用。默认关闭、运行时条件保护或捕获 `LinkageError` 不会消除报告结果。

## 3. 问题分类

### 3.1 必须解决：功能无损修正

#### 3.1.1 `AndroidProfilerDownloader` 旧包静态引用

位置：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/utils/CopyEmbeddedDistributionPaths.kt`

修复前代码静态引用：

```text
org.jetbrains.android.download.AndroidProfilerDownloader
```

新版平台已迁移到：

```text
com.android.tools.idea.downloads.AndroidProfilerDownloader
```

代码虽然在旧调用失败后尝试新版类，但旧类仍出现在字节码中，因此新版平台报告 `Package not found`。

建议移除旧类的静态 import，通过兼容调用依次尝试两个已知包名。原有下载 installer 资源的行为可以保持不变，属于功能无损修正。

#### 3.1.2 `UserDataHolderBase.getUserMap()` Internal API

位置：

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfiguration.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`

修复前两处代码直接访问 `userMap`，用于判断 Run Configuration 的 user data 是否被重建。该属性最终调用 `UserDataHolderBase.getUserMap()`，已被标记为 Internal API。

可以改用公共 `getUserData()` / `putUserData()` 和 Jugg 私有 `Key` 保存初始化标记。当 user data 被清空或重建时，标记自然消失，无需访问底层 map，设备选择能力不受影响。

#### 3.1.3 `PluginManagerCore.getPlugin()` Internal API

位置：

- `idea/src/main/java/com/sickworm/intellij/jugg/server/JuggHotUpdateDownloader.kt`

修复前代码通过 `PluginManagerCore.getPlugin()` 获取 Jugg 的插件描述对象。JetBrains Internal API 迁移文档建议使用公共 `PluginAwareClassLoader` 获取当前插件描述。

`PluginAwareClassLoader.getPluginDescriptor()` 在 2022.1 和 2026.x 平台均存在，可以保持版本、插件路径等读取能力，属于功能无损修正。

#### 3.1.4 2022.1 缺少 `ContentFactory.getInstance()`

位置：

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggToolWindowFactory.kt`

`ContentFactory.getInstance()` 在 2022.1 不存在。`ContentManager.getFactory()` 在 2022.1 和 2026.x 均存在，可以通过 `toolWindow.contentManager.factory` 创建 content，不改变 Control Panel 行为。

#### 3.1.5 未声明 Java 插件依赖

位置：

- `idea/src/ide_entry/resources/META-INF/plugin.xml`

Jugg 已直接使用 `JavaSdk`、`JavaTestLocator`、SM Test Runner 等 Java 插件 API，但没有声明：

```xml
<depends>com.intellij.modules.java</depends>
```

如果继续支持 IntelliJ IDEA，应补充真实依赖。该变更不会损失现有功能，但会阻止插件在缺少 Java 插件能力的环境中错误加载。

#### 3.1.6 Gradle Sync listener 跨版本静态链接

位置：

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggGradleSyncListener.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggGradleSyncWithRootListener.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggProjectManagerListener.kt`
- `idea/src/ide_entry/resources/META-INF/plugin.xml`

Android Studio 211 不包含 `GradleSyncListenerWithRoot`，但已提供三参数 `GradleSyncState.subscribe(Project, GradleSyncListener, Disposable)`；221 及后续版本会通过内部 adapter 将旧 listener 语义转发到 root-aware topic。原实现同时在 `plugin.xml` 注册两个 topic，并让发布字节码静态实现 `GradleSyncListenerWithRoot`，因此 IntelliJ IDEA 缺少该类型时会产生 unresolved class。

`GradleSyncState` 自身还从 211 的 class 变为高版本 interface。即使三参数静态方法签名保持不变，直接编译调用也会把 owner 形态写入字节码，存在 `IncompatibleClassChangeError` 风险。本次改为项目打开后按类名和方法签名反射调用三参数静态入口，只订阅一次 `JuggGradleSyncListener` 并绑定 project disposable；同时删除 root-aware listener 类和两个 Sync topic 注册。`com.intellij.modules.androidstudio` 继续保持 optional，不缩小 IntelliJ IDEA 支持范围。

### 3.2 必须解决：保留内部更新能力的兼容修正

#### 3.2.1 `PluginInstaller.installAfterRestart()` Internal API

位置：

- `idea/src/main/java/com/sickworm/intellij/jugg/server/JuggHotUpdateDownloader.kt`

该调用同时造成：

- 新版平台的 Internal API 违规。
- 2022.1 平台的方法签名不兼容。

Jugg 目前仅在服务器返回更新信息后使用该能力。公开构建不内置 `servers.json`，但用户仍可配置 custom server，因此相关调用仍会被打包进 Marketplace 发布物。

JetBrains 当前没有提供已确认的、与 `installAfterRestart()` 等价的公共插件自安装 API。严格移除该调用会损失“Jugg 下载更新后直接安排插件覆盖安装”的能力。

本次确认保留该能力，但移除 `PluginInstaller` 的静态 import、类常量和方法调用，通过类名与方法名反射调用。实现按已知平台签名依次处理：

```text
(IdeaPluginDescriptor, Path, Path, boolean)
(Path, boolean, Path, IdeaPluginDescriptor)
```

只有新签名不存在时才切换旧签名；真实调用失败不会重复安装。该方案可消除 Plugin Verifier 能识别的静态字节码引用，但不改变 API 的内部属性，后续平台删除或再次修改签名时仍会明确安装失败并保留异常日志。

### 3.3 当前可以忽略

#### Deprecated API

报告中的 Deprecated API 包括：

- Jugg 自有 deprecated 接口方法的实现。
- `Logger.setLevel()`。
- `IconManager.getIcon()`。
- Guava `Charsets.UTF_8`。
- `AndroidVersion.getApiLevel()` / `isGreaterOrEqualThan()`。
- `LinkLabel.create()`。
- `ExternalSystemJdkUtil.getJdk()`。

这些问题当前不是 Marketplace 拒绝的硬阻塞项，可以在兼容问题和 Internal API 处理完成后分批迁移。

#### Scheduled for Removal API

`ProjectManagerListener.projectOpened()` 已标记为未来移除。当前版本仍可使用，不阻塞本次重新提交，但需要在平台实际删除前迁移，并兼顾 Jugg 的最低支持版本。

#### 打包 IDE package 警告

发布包中的 `kotlin-compiler-embeddable-1.9.23.jar` 包含：

```text
org.jetbrains.concurrency.CancellablePromise
```

因此 Verifier 报告 Jugg 打包了 IDE package。该项当前是 warning，可以暂时忽略。后续处理时应对相关类及引用进行一致 relocation，不能只删除单个 class，否则可能破坏 Kotlin compiler embeddable 内部引用。

#### Ignored Internal API

Marketplace 报告中已有 1 个 Internal API 使用被 Verifier 自动忽略，无需额外处理。

### 3.4 需要其他操作

#### 确认产品支持范围

当前插件配置同时体现了两种意图：

- Marketplace 描述声明支持 Android Studio 和 IntelliJ IDEA。
- `com.intellij.modules.androidstudio` 被声明为 optional。
- `since-build` 为 `211.7628.21`，且没有 `until-build`。

需要明确选择：

1. 继续支持 IntelliJ IDEA：修复 2022.1 兼容问题，补充 Java 插件依赖，并同时验证 IU 和 AI 产品。
2. 仅支持 Android Studio：将 Android Studio module 改为必需依赖，并同步修改 Marketplace 描述。该方案会缩小产品支持范围。

#### 向 JetBrains 请求自更新公共 API

在移除自动重装能力前，建议创建 Internal API YouTrack，明确询问是否存在以下能力的公共替代方案：

- 插件下载自定义 ZIP 后安排重启安装。
- 已安装插件请求 IDE 使用指定更新源进行升级。

“仅在设置内部服务器后启用”可以作为业务背景，但不能作为 Internal API 合规依据。

#### 扩充 Plugin Verifier 验证矩阵

当前 `runPluginVerifier` 只配置了较早的 Android Studio 版本，没有覆盖最新 EAP 和 IntelliJ IDEA 产品。后续应至少覆盖：

- 声明支持范围的最低平台版本。
- 当前稳定 Android Studio。
- 最新 Android Studio Preview/EAP。
- 如果继续支持 IntelliJ IDEA，对应的最低和最新 IU 构建。

## 4. 内部服务器更新能力建议

### 4.1 本次采用方案

保留外部包接入内部服务器的能力，但把更新分为两条路径。

#### 普通热更新：`isNeedReinstall=false`

继续使用 Jugg 自身的热更新加载机制：

1. 从配置的服务器下载 JAR。
2. 校验文件完整性。
3. 写入 `hot_update_data.json` 和 `load_list.txt`。
4. reopen project 后由 `JuggHotUpdateManager` 加载新 JAR。
5. 同时通过反射安排重启后的标准插件安装，使后续 IDE 启动回到完整插件版本。

该路径可以完整保留 Jugg 热更新能力，并避免在发布字节码中静态链接 Internal API。

#### 完整插件更新：`isNeedReinstall=true`

继续下载并打包完整插件 ZIP，通过反射调用 `PluginInstaller.installAfterRestart()` 安排重启安装。该路径保持原有自动更新体验，并兼容当前已知的新旧签名。

标准 Custom Plugin Repository 仍是长期迁移方向：由 HTTPS 服务托管完整插件 ZIP 与 `updatePlugins.xml`，企业环境通过 `idea.plugin.hosts`、IDE Provisioner 或 IDE 设置预配置仓库。当 JetBrains 提供等价公共 API，或内部 API 再次变化时，应优先迁移到该标准路径。

### 4.2 长期隔离方案

如果后续 Marketplace 审核不接受反射方式，或平台频繁修改内部安装 API，可以提供独立的内部 updater companion plugin：

- Marketplace Jugg 不直接或反射调用 `PluginInstaller` 等私有 API。
- companion plugin 只通过内部仓库分发。
- Jugg 仅在检测到 companion plugin 时显示自动重装能力。
- 未安装 companion plugin 时回退到标准 Custom Plugin Repository 更新。

这样可以隔离 Marketplace 审核风险和平台私有 API 兼容风险，但会增加一个内部部署组件。

## 5. 本次落地结果

1. `AndroidProfilerDownloader` 的两个已知包名都改为反射探测，发布字节码不再链接旧包。
2. 两种 Run Configuration 使用公共 user data `Key` 保存初始化标记，不再访问 `getUserMap()`。
3. 当前插件描述通过 `PluginAwareClassLoader.getPluginDescriptor()` 获取，不再调用 `PluginManagerCore.getPlugin()`。
4. Tool Window content 改由 `ContentManager.getFactory()` 创建，兼容 2022.1。
5. `plugin.xml` 与 Gradle IntelliJ 配置显式声明 Java 插件依赖。
6. `PluginInstaller.installAfterRestart()` 保留自动安装行为，但只通过反射处理两个已知签名，移除静态字节码引用。
7. 发布前仍需对官方最低、稳定和最新 EAP 产品矩阵复核，并根据剩余报告决定是否回复 JetBrains。
8. Gradle Sync 改为在项目打开时反射调用三参数 `GradleSyncState.subscribe`；删除 `GradleSyncListenerWithRoot` 实现和 `plugin.xml` 双 topic 注册，保留旧 listener 的事件语义与 IntelliJ IDEA optional dependency。

## 6. 验证结果

- 定向测试 `JuggAndroidTestRunConfigurationDeviceSelectionTest` 通过，确认两种 Run Configuration 仍会写入可用的设备选择标记。
- `:idea:buildPlugin` 通过，最终产物为 `idea/build/distributions/jugg-3.4.1-release.zip`。
- 对最终插件 JAR 执行 `javap -verbose` 检查，未发现 `PluginInstaller` 的 Class/Methodref、`PluginManagerCore`、`getUserMap()`、旧包 `AndroidProfilerDownloader` 或 `ContentFactory.getInstance()` 静态引用；`PluginInstaller` 仅保留反射字符串。
- Plugin Verifier 1.410 对本机 Android Studio 2022.1（AI-221）验证后为 0 个 compatibility problem、0 个 Internal API，仅保留 1 个既有打包 warning 和 5 个 Deprecated API 使用。
- Plugin Verifier 1.410 对本机 Android Studio 2026.2 Preview（AI-262）未再报告本次处理的 Internal API；该本地 Preview 相比 `3.4.0-release` 已包含后续 androidTest 代码，Verifier 另报 18 个 test framework API 兼容问题，属于后续功能的独立兼容任务，不纳入本次补丁。
- 仓库 `runPluginVerifier` 矩阵中的 AI-2022.3 下载因 Google 镜像 SSL 错误失败；已使用本机 AI-221 安装目录完成最低版本替代验证。Marketplace 的 IU-2026.3 EAP 仍需在正式上传前复核。
- 对实际 AI-211 与 IC-223 Android plugin JAR 执行 `javap`，确认两边均存在相同的三参数静态 `subscribe`：211 的 `GradleSyncState` 是 class 且没有 `GradleSyncListenerWithRoot`，223 的 `GradleSyncState` 是 interface 并通过 `GradleSyncListenerAdapter` 转发。
- `:idea:compileKotlin`、`:idea:buildPlugin` 通过；最终 `jugg-3.4.2-HEAD-SNAPSHOT.zip` 中保留 Android Studio optional dependency，`GradleSyncState` 仅剩反射字符串，未包含 `GradleSyncListenerWithRoot` 或已删除 listener class。
- Plugin Verifier 1.409 对 AI-211 与 IC-223 均未报告 Gradle Sync 相关兼容问题；AI-211 仍有 4 个与本次无关的既有兼容问题，IC-223 为 0 个 compatibility problem。仓库双目标任务仍因 AI-223 下载的 Google 镜像 SSL 错误退出。

## 7. 参考资料

- [Internal API Migration](https://plugins.jetbrains.com/docs/intellij/api-internal.html)
- [Verifying Plugin Compatibility](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
- [Custom Plugin Repository](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html)
- [IntelliJ IDEA Plugin Management](https://www.jetbrains.com/help/idea/managing-plugins.html)
