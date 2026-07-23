# 独立 Kotlin Compiler Worker / Daemon 演进方案

> 状态：后续启动前置输入，本次不实施
> 更新时间：2026-07-23

## 1. 背景与当前决策

Jugg 当前在 Android Studio 进程内通过隔离 ClassLoader 调用项目 Kotlin compiler。DataBinding Kotlin `@BindingAdapter` 场景已增加一次性独立 KAPT 进程，优先使用 `cmdCompileEnv` 中的 Gradle Java Home，解决旧 KAPT 继承新版 Android Studio JBR 后无法访问 `jdk.compiler` internal packages 的问题。

当前已有约 80 万次实际运行，除本次 KAPT/JBR 兼容问题外，尚未观察到普通 Kotlin 编译存在普遍的宿主 JBR 兼容失败。因此当前决策是：

- 不全面迁移普通 Kotlin 编译。
- Kotlin DataBinding Adapter 的 KAPT 保持一次性独立进程。
- 普通 Kotlin、KSP、Compose 等路径继续使用进程内 `K2JVMCompilerIsolate`。
- 先记录目标架构、启动条件、TODO 和 benchmark 口径，后续由真实故障率驱动是否启动。

## 2. 当前实现

### 2.1 普通 Kotlin 编译

```text
Android Studio JBR
  -> KotlinCompilerInvoker
  -> K2JVMCompilerIsolate
  -> 项目 Kotlin compiler / embedded compiler
  -> 复用 compiler ClassLoader
```

优点是启动快、可复用 compiler ClassLoader。当前已有的宿主兼容处理包括：

- Priority ClassLoader 隔离 IDE Kotlin 与项目 Kotlin compiler。
- `KotlinCompilerHostCompat` 处理旧 Kotlin compiler 无法识别 JDK 25+ 的问题。
- Android classpath 场景通过 `-no-jdk` 避免错误挂载宿主 JDK。
- Parcelize 等 compiler plugin ClassCastException 的识别与 fallback。

### 2.2 DataBinding Kotlin Adapter KAPT

```text
SourceDataBindingProcessor
  -> KotlinCompilerProcessRunner
  -> cmdCompileEnv.JAVA_HOME（Gradle Java Home）
  -> 项目 K2JVMCompiler CLI
  -> KAPT / DataBinding processor
```

当前实现是 one-shot 子进程，不包含 IPC、常驻 Worker 或 compiler 热复用。

## 3. 可预见的兼容风险

按当前证据和发生概率排序：

1. KAPT 或 Java Processor 使用 `com.sun.tools.javac.*` internal API，受 JBR module exports/opens 影响。
2. 未来 JBR 版本与旧 Kotlin compiler 的 JavaVersion、JRT 文件系统或 JDK API 不兼容。
3. Parcelize、Compose、KSP 等 compiler plugin 与 Kotlin compiler/IDE ClassLoader 冲突。
4. Kotlin compiler、compiler plugin 的全局状态、线程或缓存污染 IDE 进程。
5. 普通无插件 Kotlin 编译与宿主 JBR 冲突；当前实际发生率最低。

这些风险说明进程隔离是更完整的架构边界，但 80 万次运行数据暂不支持立即投入全面迁移。

## 4. 长期目标架构

```text
Android Studio / Jugg
  -> 文件变化分析
  -> 增量任务编排
  -> Worker Manager
       -> IPC
Kotlin Toolchain Worker
  -> Gradle Java Home
  -> 项目 Kotlin compiler
  -> KAPT / KSP / Compose plugins
  -> Kotlin metadata / kotlin_module 处理
  -> 结构化诊断与编译产物
```

正确的长期边界是：IDE 插件只负责增量编排，所有依赖项目 Kotlin compiler 或 compiler plugin 的逻辑进入独立 Worker。

不建议直接依赖 Kotlin 官方 daemon 的内部协议。Jugg 应定义一个薄且稳定的 Worker 协议，Worker 内部再调用匹配项目版本的 Kotlin compiler。

## 5. Worker 隔离与复用模型

Worker 不应由所有工程全局共享，应按 toolchain fingerprint 复用：

```text
WorkerKey =
  Gradle Java Home
  + Kotlin compiler version
  + compiler classpath hash
  + compiler plugin classpath hash
  + JVM arguments
```

同一个 key 可以跨 module 复用；key 变化时启动新 Worker。不同 JDK、Kotlin 或 plugin 组合必须运行在不同进程中。

Worker 生命周期至少包括：

- start / handshake / ping / shutdown；
- request ID 与响应关联；
- 单 Worker 内任务串行；
- cancel、timeout 和进程树终止；
- crash、OOM、协议损坏后的重建；
- compiler/plugin classpath 变化后的淘汰；
- project close 和 idle timeout 回收；
- Worker 内存、Metaspace 和线程数量监控。

KAPT、KSP 和普通 Kotlin 不一定使用相同复用周期。Processor 或 plugin 如果存在跨请求状态污染，可以采用有限次数复用或任务后重启策略。

## 6. IPC 边界

IPC 不传递 IntelliJ、PSI 或 Kotlin compiler 内部对象，只传稳定数据和文件路径。

建议的最小协议：

```text
Start / Handshake
Ping
CompileRequest
CompileResponse
CancelRequest
Shutdown
```

`CompileRequest` 至少包含：

- protocol version、request ID；
- project/module/variant；
- source、classpath、output 路径；
- compiler arguments 和环境变量；
- task type：Kotlin、KAPT、KSP；
- timeout 和 toolchain fingerprint。

`CompileResponse` 至少包含：

- exit code 和结束原因；
- 结构化 diagnostics；
- generated files 和 outputs；
- compiler、plugin、JDK fingerprint；
- 初始化、编译、输出整理耗时；
- Worker 是否仍可复用。

初版可以使用持久 stdin/stdout framing，暂不需要引入 gRPC。stdout 协议数据必须与 compiler stdout/stderr 分离。

## 7. 需要迁移出 IDE 的逻辑

全面隔离不能只迁移 `kotlinCompile.exec()`。以下 compiler-dependent 逻辑也应进入 Worker：

- 项目 Kotlin compiler 初始化和版本确认；
- compiler/plugin ClassLoader；
- KAPT、KSP、Compose、Parcelize plugin 加载；
- `.kotlin_module` load / merge / save；
- Kotlin metadata 读取与兼容处理；
- compiler 输出中依赖 compiler API 的解析；
- compiler recreate、plugin disable 和 JVM target retry；
- Kotlin PSI、resolve、VFS cache 清理。

当前隔离模式仍会在 IDE 内调用 `kotlinCompile.initIfNeeded()` 和 `IKmModuleMergerForCompilation`。未来真正隔离时必须消除这部分项目 compiler 加载。

## 8. 分阶段 TODO

### P0：观测与启动判断

- [ ] 记录 IDE JBR、Gradle Java Home、Kotlin compiler、plugin 列表和执行模式。
- [ ] 分类统计 INTERNAL_ERROR、compiler recreate、plugin disable 和宿主 JDK 异常。
- [ ] 记录进程内、one-shot、未来 Worker 的初始化与编译耗时。
- [ ] 明确全面演进的启动阈值，由真实故障率和性能数据驱动。

### P1：完善 one-shot 独立进程

- [ ] 使用 argfile 解决 Windows 命令行长度限制。
- [ ] 校验 Gradle Java Home 和 `bin/java`。
- [ ] 区分用户取消、超时、进程崩溃和编译失败。
- [ ] 让 heap、timeout 和 JVM arguments 可配置。
- [ ] 分离 stdout、stderr 和结构化 diagnostics。
- [ ] 完成 macOS、Windows、Linux E2E。

### P2：Worker 进程与协议

- [ ] 建立独立 Worker entrypoint。
- [ ] 定义 protocol version、framing 和 request/response DTO。
- [ ] 实现 handshake、ping、compile、cancel、shutdown。
- [ ] 实现 WorkerKey 和进程复用。
- [ ] 实现 crash recovery、idle recycle 和 project close cleanup。

### P3：迁移 compiler-dependent 逻辑

- [ ] 将 compiler 初始化和 plugin 加载迁入 Worker。
- [ ] 将 Kotlin metadata 与 `.kotlin_module` 处理迁入 Worker。
- [ ] 将 retry/fallback 和结构化诊断迁入 Worker。
- [ ] IDE 进程不再加载项目 Kotlin compiler。

### P4：渐进接入

- [ ] 先让当前 DataBinding KAPT 使用 Worker，验证复用和回收。
- [ ] 增加普通 Kotlin 的隐藏实验开关。
- [ ] 对明确的宿主兼容错误增加 Worker retry。
- [ ] 根据 benchmark 和线上数据决定是否扩大默认范围。
- [ ] 始终保留进程内模式作为回滚路径。

## 9. Benchmark TODO：初始化 / 首次编译 / 二次编译

> 本次只定义 benchmark 口径，不增加 benchmark 代码。Worker/Daemon 原型启动时必须先实现并执行该 benchmark。

### 9.1 目标

量化独立进程方案的固定启动成本、compiler 冷启动成本和 Worker 热复用收益，避免只比较单次总耗时。

必须分别记录：

| 阶段 | 计时范围 |
|------|----------|
| 初始化 | 启动 JVM、建立 IPC、完成 handshake，到 Worker 可以接收编译请求；不预加载 compiler |
| 首次编译 | 同一 Worker 第一次真实 Kotlin 编译，包含 compiler ClassLoader、plugin 初始化和实际编译 |
| 二次编译 | 不重启 Worker，对同一源码执行第二次编译，验证热复用耗时 |

### 9.2 对比组

至少包含三个模式：

1. 当前进程内 `K2JVMCompilerIsolate`：首次编译、二次编译。
2. 当前 one-shot 独立进程：首次、二次均为冷进程，用作下限能力和启动成本参考。
3. 未来 Worker/Daemon：初始化、首次编译、二次编译。

普通 Kotlin 是强制场景；KAPT、KSP1、KSP2、Compose 作为扩展场景分别记录，不能混合为一个平均值。

### 9.3 测量约束

- 使用真实 `android_demo_project` module、Gradle Java Home 和项目 Kotlin compiler。
- Gradle assemble、测试 fixture 初始化、dex、部署不计入 Kotlin compiler benchmark。
- 首次和二次编译使用相同 source、classpath、compiler args 和 WorkerKey。
- 二次编译前清理本轮输出文件，但保留 Worker、compiler ClassLoader 和允许复用的 compiler cache。
- 每个模式至少独立运行 10 组；记录原始样本、median、p90、p95、min、max。
- 每组 Worker 模式都重新启动 Worker后再测初始化，避免跨组污染。
- 同时记录进程 RSS、IDE heap、Worker heap、Metaspace 和线程数。
- 编译必须断言成功且输出 class、`.kotlin_module` 与当前进程内结果一致。
- benchmark 默认不进入常规测试，必须通过显式 system property 启用。

### 9.4 建议测试落点

```text
idea/src/test/java/com/sickworm/intellij/jugg/compile/KotlinCompilerWorkerBenchmarkTest.kt
```

层级：L3 benchmark，使用真实 demo 编译产物，不使用 Mockito 模拟 compiler 或进程。

建议启用方式：

```bash
./gradlew :idea:test \
  --tests 'com.sickworm.intellij.jugg.compile.KotlinCompilerWorkerBenchmarkTest' \
  -Dbenchmark.kotlin.worker=true \
  -Dbenchmark.output.dir=/tmp/jugg-kotlin-worker-benchmark
```

建议输出：

```text
environment.json
in_process.json
one_shot_process.json
worker_daemon.json
comparison.json
```

benchmark 初期不设置硬编码性能阈值，只提供数据和对比。是否将 Worker 扩大到普通 Kotlin 编译，由稳定性、首次耗时、二次耗时和资源占用共同决定。

## 10. 启动条件

满足以下任一条件时重新评估启动 Worker/Daemon：

- 普通 Kotlin 出现无法通过局部兼容补丁解决的 JBR/JDK 问题；
- Compose、KSP、Parcelize 等 compiler plugin 的 ClassLoader 冲突显著增加；
- compiler INTERNAL_ERROR、IDE crash 或 compiler recreate 比例持续增长；
- 新版 Android Studio JBR 导致旧项目 Kotlin compiler 大面积不可用；
- one-shot 隔离场景增加，JVM 冷启动成本已需要统一复用；
- benchmark 证明 Worker 二次编译接近或优于进程内方案，且资源回收可控。

## 11. 当前非目标

- 本次不改变普通 Kotlin 默认编译路径。
- 本次不增加 Worker、IPC 或 daemon 代码。
- 本次不增加 benchmark 测试代码。
- 不因理论风险替换已经通过 80 万次运行验证的进程内路径。
- 不直接绑定 Kotlin 官方 daemon 的内部协议。

## 12. 相关实现与文档

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/K2JVMCompilerIsolate.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerInvoker.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerHostCompat.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerProcessRunner.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt`
- `docs/ai_knowledge/09_plugin_runtime_debug.md`
- `docs/task/databinding_setter_store_incremental_design.md`
- `docs/task/databinding_setter_store_incremental_implementation_plan.md`
