# Issue #79：Kotlin 重试诊断与首次错误保留

## 问题与范围

Issue：https://github.com/tencentmusic/jugg/issues/79

现场报告首次 Kotlin INTERNAL_ERROR 后，重试产生大量 unresolved reference，掩盖首次基线 class 读取异常。当前实现的 Parser 立即输出源码错误，Invoker 直接返回重试结果，可静态确认这两个行为。

按用户批准范围实施：不修改任何重试触发条件和次数控制；仅控制 Parser 的用户可见输出，保留 Invoker、独立进程 runner 等其他日志。基线目录被谁改写尚无原始现场证据，本次不处理该原因，也不增加自动 Gradle 回退。

## 实现

- Parser 增加输出开关，静默时仍解析诊断、异常、产物并写 debug；结果读取不再隐式输出错误。
- 显式发布去重诊断；异常摘要保留 Caused by，便于呈现缺失 class 路径。
- Invoker 所有尝试暂缓 Parser 诊断。首次失败且不重试时发布首次诊断；重试成功返回成功及产物；重试失败仅发布并返回首次错误结果，产物为空。
- 重试仍失败时，在首次诊断前以 info 输出一行提示：`Kotlin compiler retry did not resolve the problem. Showing the original compilation errors below.`
- 原有四个重试调用出口统一使用局部恢复方法，首次结果保存在调用栈中；成功缓存、产物收集、状态清理仍沿用原流程。

## 验证

测试价值：最终诊断可见性、首因选择和产物是否可继续使用是稳定用户行为，值得自动化；通过 Mockito 替换 Kotlin 编译器执行，不引入生产测试 seam。

- 修改前新增 Parser 回归因缺少输出开关和显式发布 API 编译失败。
- L1：KotlinCompilerOutputParserTest，8 项，覆盖静默收集、明确发布、KAPT 去重、异常原因及既有诊断/产物解析。
- L2：KotlinCompilerRetryTest，4 项，覆盖首次成功、普通失败、重试成功、重试失败；断言诊断与产物。
- 真实编译：KotlinAndroidTestInternalCompileTest，4 项，覆盖当前模块和 androidTest internal 可见性。
- 既有增量编译流程等价回归：IncrementalCompilerHelperTest，18 项。
- 定向命令：`./gradlew :main:test --tests '*KotlinCompilerOutputParserTest' --tests '*KotlinCompilerRetryTest' --tests '*KotlinAndroidTestInternalCompileTest' --tests '*IncrementalCompilerHelperTest' --console=plain`
- 结果：34 项通过，git diff --check 通过。未执行设备端 Run → 部署 L3，也未在报告者 Windows 原始工程复现。

## 文档

同步 02_compile_source.md 的 Parser 和重试结果契约。已检查 Wiki 编译失败排查页，其“先处理第一条明确错误”及既有恢复步骤仍有效，无需修改。
