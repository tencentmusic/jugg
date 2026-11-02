# androidTest 访问被测模块 internal 的增量编译修复

## 问题与证据

JOJO `WnsHeartbeatSmokeTest.kt` 在 Jugg Kotlin 增量编译中访问 `transport`、设置及清除 listener 时被 internal 可见性检查拒绝。原始 `compile_2026-11-02_11-09-01.0.log` 的 12:49:41 invocation 中，主模块 debug output 已进入 classpath，friend path 却只有 debugAndroidTest output。

## 修复范围

在 KotlinCompilerInvoker 构造 friend paths 的边界保留当前 baseline（含原 KMP 隔离视图），仅当当前模块为 androidTest 时，从直接依赖中匹配同 module root、对应主 variant 的非测试 owner，追加其 Kotlin output。其他依赖不获得内部可见性，普通编译沿用原路径。不改 JOJO 源码及 internal 声明。

## 验证

测试具有稳定可观察结果：真实 Kotlin 编译成功或因内部可见性被拒绝；使用 Mockito 隔离编译上下文，不增加生产测试 seam。L1 owner 为 KotlinAndroidTestInternalCompileTest，覆盖被测模块内部成员读写、其他依赖访问被拒、测试自身 baseline 访问以及普通增量编译。

修改生产代码前，首个真实编译回归稳定复现 transport/listener 不可访问，另外两项用例通过。最终定向执行 KotlinAndroidTestInternalCompileTest 与 KotlinCompilerInvokerArgsTest，15 项测试全部通过（4 项真实编译回归、11 项既有参数回归）。`git diff --check` 通过。

## 文档与边界

同步 02_compile_source.md、06_android_test.md。此修复恢复既有 androidTest 增量编译契约，现有用户操作、支持范围与排查步骤未变化，无需修改 Wiki。未在运行中的 Android Studio 安装插件或重新执行 JOJO 真机测试。
