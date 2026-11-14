# ARouter custom compiler 适配与 Demo

## 目标和证据

目标是在 Jugg 编译 Java/Kotlin 前运行目标项目提供的 ARouter JSR 269 处理器，并把新生成的 Java 交给原有 Kotlin、Java、D8 和部署流程。处理器、annotation、API 和处理器运行依赖随项目解析，不锁定附件的哈希或二进制契约。

既有 Java 编译关闭 APT，普通 Kotlin 编译关闭 KAPT。附件处理器以 `META-INF/services/javax.annotation.processing.Processor` 注册 `AutowiredProcessor`、`InterceptorProcessor`、`RouteProcessor`，并非 Jugg custom compiler。临时 javac 探针表明 class-name 输入会让 `InterceptorProcessor` 校验失败；Java 源码输入可处理三种注解。附件 API 使用 `com.tencent.yb.routes` 作为生成包，不能与 Demo 原先的标准 ARouter 1.5.2 产物混用。

原 Demo 的 Gradle 7.4、AGP 7.3、Kotlin 1.9 与附件不兼容：API 含 Kotlin metadata 2.1，旧 D8 处理附件 annotation JAR 时失败。已将根工程切换到 Kotlin 2.1.0、AGP 8.7.2、Gradle 8.11.1、JDK 17 和 minSdk 23，并把附件依赖、Java/Kotlin 混合路由、字段注入及拦截器直接接入既有 `:app`，供人工验证。具体操作见 Demo 根目录的 `AROUTER_FIXTURE.md`。

## 实现

- ARouter 项目专用适配器存放在 `git@git.woa.com:wormchen/jugg-yb-custom-compilers.git`，按 `custom_compilers/README.md` 克隆到 `custom_compilers/src/custom`，经 `:custom_compilers:jar` 和 `ICompilerCreator` SPI 交付，不内嵌 ARouter 产物。Jugg 主仓库只保留通用扩展入口。目标模块的 `annotationProcessor`、`kapt` 解析结果组成处理器路径；目标项目编译 classpath 供 javac 使用。
- `SourceCompiler.prepareSourceCompile()` 已在语言编译前调用 `JuggAptCompiler`。JuggApt 额外运行实现 `IJuggAptProcessor` 的 custom compiler，无需新增编译阶段入口，也避免 Java、Kotlin 混合输入执行两次。
- 适配器从模块完整源码提取 `@Route`、`@Autowired`、`@Interceptor` 及其声明、相关常量和参数，按模块与变体保存签名及生成 Java。签名还包括 Gradle 生成源码基线的内容指纹。签名相同且缓存文件内容完整时，不启动 javac 或 KAPT；直接复用本地生成源码。方法体变化不改变签名。签名变化时对模块完整 Java 源码和 Kotlin KAPT stubs 执行原始处理器。
- 生成的 Java 在 Kotlin 编译前加入 Java source roots，并在后续 Java 阶段编译。外部适配器失败、生成文件缺失、生成 Java 编译失败都停止本轮源码编译，提示完整 Gradle 构建；原有内置 JuggApt 仍保持自己的降级语义。
- 生成类名消失时停止增量编译，要求完整 Gradle 构建清除旧基线。纯删除文件的变化仍受现有文件变更入口限制，需手动完整构建。

## 验证

按用户要求不新增测试用例，以实际产物和定向编译作为替代验证。

- Jugg `:custom_compilers:jar :idea:compileKotlin` 使用 JDK 17 验证独立仓库源码及通用接线。
- `aigc-bot-client` 根工程的 `:app:assembleDebug` 使用 Gradle 8.11.1、AGP 8.7.2、Kotlin 2.1.0、JDK 17 通过。
- 根工程的 Gradle/KAPT 生成 6 个 ARouter Java 文件：原有 `app` 路由和新增 Java/Kotlin 路由的 Group、Root、Providers、Interceptors，以及 Java Activity 的 Autowired 辅助类；`demo` Group 中同时包含 `/demo/java`、`/demo/kotlin`。APK 已产生。
- Jugg Run 的处理器跳过、重生与部署由用户在 Android Studio 中手工验证；仓库内编译验证不等同于这项端到端验证。

## 边界

签名是源码声明提取，不是完整 Java/Kotlin 语义分析。复杂注解别名、跨模块常量和间接继承结构变化应先走 Gradle 完整构建；不把未知输入误报为已验证的增量生成。适配器依赖当前项目提供处理器和运行依赖；缺失时明确失败。处理器异常不会自动转为 Gradle 回退。

## 现场后续修复

`aigc-bot-client` 在 2026-10-09 20:23 和 20:25 的 Jugg 增量编译中，ARouter 扩展已加载，KAPT stub 生成成功，但适配器直接调用 `ToolProvider.getSystemJavaCompiler()` 得到空值。错误留在 `CompileResult`，上层将失败原因改写为通用的 `Compile failed`，Run 输出只提示查看日志。

适配器复用 Jugg 已有的 `JavaCompilerInvoker.getJavaCompiler()` 备用路径。处理器类路径包含模块依赖，解决 `arouter-annotation` 不在处理器 JAR 中造成的 `NoClassDefFoundError`。KAPT stub 阶段传入处理器依赖，只处理带 ARouter 声明的 Kotlin 源文件，并在没有 Java stub 时明确失败；否则 Kotlin 路由会从生成表中静默消失，或被无关 Kotlin stub 中的未解析引用阻断。Demo 的配置生成任务保留三个空规则列表，避免构建覆盖手动填写的配置。

历史调试中曾用回归测试覆盖公共失败原因传递、Run 日志输出和直接源码诊断去重；公共层改动及对应测试现已撤回。先前 Demo 中修改 `KotlinRouteActivity` 的 `@Route` 路径后，Jugg 增量编译生成 6 个 ARouter Java 源文件、7 个文件全部成功。恢复原路径再次增量编译成功，检查生成的 `ARouter$$Group$$demo.java` 同时包含 `/demo/java2` 和 `/demo/kotlin`。该次只执行编译，未部署到设备。

## 独立仓库与日志职责调整

ARouter 源码和 SPI 注册文件已从 Jugg 主仓库移至独立仓库，并修正 custom 模式的 resources 路径，使 SPI 文件进入 JAR。适配器仅在确实进入生成时输出启动 info；成功后输出每个生成文件的绝对路径；异常在适配器内输出 warn 和堆栈；已经启动的生成在 finally 中输出带秒数的结束 info。向公共链路只提交不含项目异常细节的失败标记，保留 `failClosed` 的失败语义。此前为 ARouter 诊断加入的公共失败原因展示已撤回。

本次日志改动不新增自动化测试：普通日志文案不满足 `06_testing.md` 的测试价值门禁。JDK 17 下 `:custom_compilers:jar :idea:compileKotlin` 通过，JAR 包含 ARouter 类和 SPI 注册文件且不含 Demo 类；公共路径的 `IncrementalCompilerHelperTest`、`JuggCompileHelperTest` 定向通过。历史失败现场及上述恢复编译记录作为迁移前的行为证据；本次尚未在目标 Android Studio 项目中观察新版 info/warn 日志。

## 历史适配器兼容性证据

以下为内置 ARouter 能力落地前的项目专用适配器验证，保留探针输入、产物及证据边界，不代表当前内置实现。

通用的是 Jugg 的 SPI、`IJuggAptProcessor` 接线及调用项目处理器的方式；当前 YB 适配器不是通用 ARouter 实现。它只从 `annotationProcessor` / `kapt` 依赖寻找处理器，要求其中一个 JAR 在 SPI 文件里同时注册固定的 `AutowiredProcessor`、`InterceptorProcessor`、`RouteProcessor` 类，并用这三个类名显式启动 javac；要求 `AROUTER_MODULE_NAME` 选项；只识别 `com.alibaba.android.arouter.facade.annotation` 包下的 `Route`、`Autowired`、`Interceptor` 声明。缓存签名对 library dependency 只跟踪名称含 `arouter` 的项，对 Gradle 生成 Java 只跟踪文件名含 `ARouter` 的项。生成包 `com.tencent.yb.routes` 来自已验证项目的处理器，并非适配器硬编码的输出包。

本地 Gradle 缓存中的标准 `com.alibaba:arouter-compiler:1.5.2`、`arouter-annotation:1.0.6`、`arouter-api:1.5.2` 满足上述 SPI、类名、注解和选项契约。JDK 17 下以 `-proc:only`、同样的三个处理器类名及 `AROUTER_MODULE_NAME` 对 Java `@Route`、`@Autowired`、`@Interceptor` 运行探针，显式加入旧版 `com.android.support:support-fragment:28.0.0` 后成功生成标准包 `com.alibaba.android.arouter.routes` 下的五个 Java 文件。此探针只证明处理器入口和 Java 生成契约兼容，不能证明开启 Jetifier 的实际项目类路径、Jugg 整轮增量、Kotlin KAPT、缓存失效及部署兼容。

Project Info 保存的是 Gradle artifact view 返回的实际文件，不存在统一的“Jetifier 之前/之后”阶段。`android.enableJetifier` 属性虽被读取，但不决定读取器的文件选择；AAR 解析出的类 JAR 可位于 `transformed/jetified-*`，本地 `files(...)` JAR 则由读取器明确恢复为原始文件。已保存的标准版 Demo Project Info 中，`arouter-api:1.5.2` 是 Jetifier 后的 AndroidX `classes.jar`，`arouter-compiler:1.5.2` 与 `arouter-annotation:1.0.6` 是原始 Maven JAR。标准 `RouteProcessor` 仍硬编码查找 `android.support.v4.app.Fragment`，而 Demo 类路径只有 `androidx.fragment:fragment`；以该 Project Info 的实际 JAR 列表和适配器相同的 `-classpath` / `-processorpath` 运行 `javac -proc:only`，处理 Java Activity `@Route` 时在 `RouteProcessor.parseRoutes` 失败，生成 0 个文件。旧版 support-fragment 手动加入可让独立探针成功，但不能据此推断开启 Jetifier 的 Project Info 自带该旧类。

本地 Jetifier `1.0.0-beta10` 实测会改写标准 compiler 的反射类名字符串：对原始 `arouter-compiler:1.5.2` 执行转换后，`RouteProcessor` 中的 `android.support.v4.app.Fragment` 变为 `androidx.fragment.app.Fragment`。将转换后的 JAR 替换到同一份 Demo Project Info 类路径，重复 Java Activity `@Route` 探针成功生成 3 个文件。判断是否需要转换应对比处理器实际查找的类型与本轮编译有效类路径中的类型；`android.enableJetifier` 开关和 APK 包含的类型都不能单独代表该类路径。若两侧都使用旧 Support，本身无需转换；若两侧都使用 AndroidX，同样可直接运行。此探针仍不覆盖完整 Jugg 增量流程。

APK DB 的 `apk_info.is_enable_desugar` 已在写入时按解析出的 class 计算。ARouter 的兼容库类型也可利用已存在的 `ICompileContext.containsApkClass()`，直接查询 `Landroidx/fragment/app/Fragment;` 与 `Landroid/support/v4/app/Fragment;`，不必先新增数据库列。YB 的已保存 APK DB 只含前者。APK 查询可提供已构建基线的类型信号；两者都存在或都不存在时不能强行归类，而 APK 中存在某类型也不保证本轮 javac 类路径含该类型。选择处理器后仍需验证 javac 能解析其要求的 Fragment 类型，否则单独转换 compiler 不能使缺失类型出现。

已保存的 YB Project Info 中，本地 `arouter-api-release.aar` 的类 JAR 位于 `transformed/jetified-*`，本地 `arouter-annotation.jar` 与 `arouter-compiler.jar` 指向原始文件；该魔改 compiler 的原始字节码已改为查找 `androidx.fragment.app.Fragment`。用其实际类路径运行同一 Java Activity 探针成功生成 3 个路由文件。两次探针均不是 Jugg 完整增量编译验证；评估标准版或其他 fork 时，应检查处理器实际字节码和 Project Info 类路径中的 Fragment 类型是否一致。

注解和相关常量/继承事实靠源码正则提取，不是完整 Java/Kotlin 语义分析；跨模块常量、复杂别名、间接继承或未被签名覆盖的生成条件变化，可能使缓存误判为无需运行处理器。旧生成类消失时适配器要求完整 Gradle 构建，纯删除文件还依赖上游变更入口发现。评估新版本或 fork 时，应先核对处理器类名与 SPI、注解包与名称、选项、生成文件命名及实际增量输出，再决定是否复用此适配器。
