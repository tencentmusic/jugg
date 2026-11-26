# Standalone Jugg CLI Step 9 实施记录

## 1. 目标

完成固定 Android Studio Quail 1 的 Java 11 standalone deployer，实现可发行的 install、class HOT RELOAD 和 resource HOT RELOAD 基础能力，不依赖完整 Android Studio runtime JAR。

## 2. 已确认事实

- Android Studio 来源固定为 `/Applications/Android Studio Quail 1.app`。
- `sdk-tools.jar` 中目标 deployer class major version 为 65，Java 11 不能直接加载。
- Quail 提供 arm64-v8a、armeabi-v7a、x86、x86_64 四个 installer binary。
- 指定 Quail 资源目录中没有独立的 Apply Changes agent 或 app-server 文件；`transport/native/agent` 不属于 standalone deployer，不纳入资源。
- 当前仓库不存在 `:deploy_compat:standalone_deployer`，`./gradlew :deploy_compat:standalone_deployer:tasks` 稳定失败。
- 当前可使用 Pixel 7、API 36、arm64-v8a 真机验证部署行为。

## 3. 批准实施范围

- 在 `deploy_compat/standalone_deployer/` 新增 `:deploy_compat:standalone_deployer`，固定 Java 11。
- 只迁入 install、APK model/cache、overlay diff、`OptimisticApkSwapper` 实际调用的传递闭包，保留 `com.android.tools.deployer` 原包名。
- 禁止提交或运行时加载完整 Quail `sdk-tools.jar` 和 Java 21 class。
- 仅引入实际需要的 ddmlib、protobuf 和 utility 依赖；只有 Maven 依赖无法提供相同 shaded protocol API 时，才纳入最小 Quail Java 8 protocol JAR。
- 新增 `IApplyChangesExecutor` 和 `StandaloneApplyChangesExecutor`；Step 10 对齐共享方法签名，让 `IAsDeployerCompat` 直接继承该接口，再迁移 IDEA 部署编排。
- 新增 `JuggResourceManager`，通过统一全局写锁释放并校验版本化 deployer 资源。
- standalone daemon 启动时执行 deployer resource preflight，但不注册 Step 10/11 的部署编排或 MCP deploy 能力。
- metadata 记录 Quail build、来源、installer hash、协议版本、迁入 class 清单及 hash、license/source notice。
- 同步 standalone 设计、代码地图和兼容层知识库。

## 4. 验证范围

- L1：metadata、SHA-256、资源原子释放、版本不匹配失败。
- L2：纯 JVM 创建 install session、APK/cache/overlay 确定性契约。
- 静态架构守卫：class major version 不超过 55，不包含 Quail Java 21 class 或完整 Android Studio runtime JAR。
- L3 真机：base install、class HOT RELOAD、resource HOT RELOAD，并校验进程和 Activity 不发生非预期重启。
- 回归 `CmdLineTest`、`:cmd_line:distZip` 和相关定向编译。

## 5. 不在本 Step 范围

- 不下沉 IDEA deploy、recover、retry 编排。
- 不注册 standalone MCP deploy 能力。
- 不实现完整 compile → deploy 用户链路。
- 不支持多个 standalone deployer 版本。
- 不使用 Direct Overlay 替代 Apply Changes。
- 不打包 profiler transport agent 等无关 Android Studio 资源。

## 6. 范围变更规则

如果实现发现必须修改 Step 10 的共享部署 lifecycle、IDEA 主路径或新增未批准的 runtime 抽象，停止实现并重新提交范围评审。

## 7. 实施结果

- 新增 `:deploy_compat:standalone_deployer`，所有自产 class 与本地 protocol JAR 的 class major version 均不超过 55。
- 迁入 50 个 Quail 顶层 deployer class 的最小传递闭包；原始 class SHA-256 清单保存在 `SOURCE_CLASSES.sha256`，不提交完整 `sdk-tools.jar`。
- 打包 arm64-v8a、armeabi-v7a、x86、x86_64 installer，metadata 同时校验 Apache 2.0 license、NOTICE、来源清单和 protocol version `c52d6b25`。
- `JuggResourceManager` 在固定全局写锁内原子释放资源，校验并修复损坏文件，同时恢复 installer executable bit。
- `StandaloneApplyChangesExecutor` 已覆盖 install session、full/delta install、APK parse/cache、overlay update、`OptimisticApkSwapper` 与错误映射。
- standalone daemon 启动时执行资源 preflight；Step 10 的 deploy lifecycle 和 MCP deploy capability 未提前迁移。

## 8. 真机结论

- 设备：Pixel 7，API 36，arm64-v8a。
- baseline full install 成功并启动 demo。
- 方法体从 `lowercase()` 修改为 `uppercase()` 后，直接 class HOT RELOAD 生效，PID 不变，Activity 不重启。
- asset 从 `Resource-V1` 修改为 `Resource-V2` 后，resource full swap 生效，PID 不变，Activity 仅发生一次协议要求的预期重启。
- Quail 标准 resource overlay 写入后需要 `restartActivity=true` 刷新已有 `Resources/AssetManager`；该行为与现有 IDEA `JuggDeployer.fullSwap` 一致，不属于异常重启。

## 9. 验证命令

- `./gradlew :main:test --tests com.sickworm.intellij.jugg.project.runtime.JuggResourceManagerTest`
- `./gradlew :deploy_compat:standalone_deployer:test --tests com.sickworm.intellij.jugg.deploy.run.StandaloneApplyChangesExecutorTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerResourceTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerArchitectureTest`
- `./gradlew :deploy_compat:standalone_deployer:test --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerDeviceFlowTest -Dstandalone.deployer.device.serial=<serial> -Dstandalone.deployer.baseline.apk=<baseline> -Dstandalone.deployer.class.apk=<class> -Dstandalone.deployer.resource.apk=<resource>`
- `./gradlew :cmd_line:compileKotlin :cmd_line:distZip`
- 使用 Java 11 启动 distribution 内 `jugg-standalone`，确认 daemon 正常启动并完成资源预检。

存量 `DeployCompatArchitectureTest#deployment cache store stays independent from studio deployer runtime` 仍引用迁移前的 IDEA cache store 路径，整类运行会在查找旧文件时失败；该问题与 Step 9 无关，本次未混入修复。其余三个 compat 架构方法已单独通过。
