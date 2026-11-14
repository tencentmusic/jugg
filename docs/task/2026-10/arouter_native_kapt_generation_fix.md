# ARouter 混合源码使用原生 KAPT 生成

## 问题与证据

2026-10-13，aigc-bot-client 修改 `JavaRouteActivity.java` 后触发完整 ARouter 聚合生成。现场日志 `compile_2026-10-13_13-16-38.0.log` 在 13:20:05.273 记录 javac 无法解析 stub 中的 `import com.example.logalias.CrossModuleLog`。该名称是 Kotlin typealias，没有对应 Java class；实际目标 `com.sickworm.jugg.demo.kmp.CrossModuleLog.class` 已存在。使用现场 classpath 对临时 stub 做对照编译：原文件失败，仅移除该 import 的副本成功。

旧 jugg-yb-custom-compilers 只为含 ARouter 注解的 Kotlin 文件生成 stub，2026-10-10 成功日志只包含两个 Kotlin 输入，因此没有遇到这个无关源码中的 import。但裁剪输入会遗漏未带注解、尚未编译的新 Kotlin 依赖，不能作为完整修复。

当前 runner 把完整 Kotlin 输入生成的 stub 交给普通 javac。现场 Kotlin 2.1.0 的 `KaptJavaLog` 会处理 stub import 中的符号解析诊断，普通 javac 不包含这层规则。修复应保留完整源码输入，并由 KAPT 完成混合语言生成。

## 实施

- 含 Kotlin 的模块使用已有 `KotlinCompilerInvoker` 的 `stubsAndApt` 模式；完整业务 Java/Kotlin 文件作为同一次调用的显式输入，不按注解裁剪。
- `Options.kaptProcessors` 指定真实 ARouter SPI 名称，每个名称分别传递一个 `-P plugin:org.jetbrains.kotlin.kapt3:processors=...`，不自动执行其他 processor。
- 显式设置空 `javaSourceDirs`，避免重复扫描 Gradle 或 Jugg 旧生成目录。Java 文件已显式传入，仍参与完整聚合。
- 从 KAPT 临时源码目录返回生成结果；纯 Java 模块继续使用既有 `javac -proc:only` 路径。未调用方的默认 KAPT 行为保持不变。
- 保留生成失败、生成集合收缩时不发布，以及成功后 checkpoint 发布和 pending 消费的契约。正常 Kotlin → Java → D8 编译继续处理生成源码。

本次只修改生成边界，不调整源码/class 比较、ConstRef、Fragment/Jetifier 策略、外部 processor SPI、部署或重试契约。不删 import，不通过忽略普通 javac 错误伪造成功。

## 验证

测试价值门禁通过：需要保护混合源码的真实索引、未编译新类型的可解析性、processor 执行范围及失败时已发布产物的完整性。复用 `ARouterJuggAptProcessorTest`、`JuggAptCompilerTest`，编排采用已有真实 SourceCompiler → Kotlin/Java → D8 Flow，不增加生产测试 seam。

新增回归先在旧实现上失败，直接诊断为 `import demo.alias.PayloadAlias` 找不到符号；证据保存为本次运行的 `/tmp/jugg-kapt-red.log` 与 `/tmp/jugg-kapt-red.xml`。修复后覆盖：

1. 混合路由源码使用 typealias，并引用未编译、未带注解的新 Kotlin 父类和返回类型；生成、语言编译、D8 及实际路由索引加载成功，真实 RouteProcessor 只初始化一次。
2. classpath 存在会抛异常的无关 processor，旧生成目录存在无效 Java 源码；只执行指定 ARouter processor，完整 Java/Kotlin 路由均生成。
3. 选定 processor 初始化抛异常时 failClosed，已有发布目录逐字节保持不变。
4. aigc-bot-client 定制 compiler、annotation、API 及依赖真实产物走混合 Flow，typealias 和新 Kotlin 类型通过，`app` 属性及定制路由包生成正常。

定向回归命令：

```bash
JAVA_HOME=/Users/wormchen/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
ANDROID_HOME=/Users/wormchen/Library/Android/sdk \
JUGG_AROUTER_FIXTURE=/Users/wormchen/IdeaProjects/demo/aigc-bot-client/arouter-fixture \
./gradlew :main:test --tests '*ARouterJuggAptProcessorTest' \
  --tests '*JuggAptCompilerTest' --tests '*KotlinCompilerInvokerArgsTest' :idea:compileKotlin
```

真实工程生成阶段替代验证：读取现场日志中的 Kotlin 2.1.0 toolchain、插件、classpath 和编译选项，仅将生成模式调整为上述 KAPT 路径、限定处理器并将输出重定向到临时目录；使用当前变体的 71 个 Kotlin 和 55 个 Java 业务文件。编译器退出码 0，生成 6 个定制 ARouter Java 文件，包含当前 Java/Kotlin 路由和八参数 `RouteMeta.build`。首次重放误纳入未启用的 `splitClassLoader` source set，随后按现场源码目录修正一次后通过；没有修改业务工程或现场产物。

最终定向回归 88 条全部通过：ARouter owner 66 条、JuggApt owner 11 条、Kotlin 参数 owner 11 条，0 失败、0 跳过；`idea:compileKotlin` 通过，没有排除构建任务。IDE 对两处生产代码的诊断无错误。中英文 Wiki 镜像/链接检查、production build 和 HTML 新内容检查均通过，`git diff --check` 通过。

实际安装插件后的 IDE Run、真机部署未验证；重放仅证明现场 toolchain 和完整业务源码的生成阶段通过。

## 文档

同步 `02_compile_source.md`、`02_compile_custom_ui.md`，以及中英文 Wiki `capabilities/compile/custom-compiler.md`。更新原 class 输入比较方案的后续修复说明。
