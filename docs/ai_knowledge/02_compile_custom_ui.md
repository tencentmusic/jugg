# 编译系统：自定义编译器与编译交互

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述两个扩展面：

- 自定义编译器如何从配置 jar 变成 `ICompiler`，以及如何插入增量编译阶段。
- 编译流程如何通过 `CompileUiHandler` 与 IDE/CLI 交互。

不展开内置编译主链，主流程见 `02_compile_core.md`；IDE 运行配置和任务调度见 `04_engineering_ide.md`。

---

## 2. 核心源码索引

| 类 | 文件 | 作用 |
|----|------|------|
| `CustomCompilerManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/CustomCompilerManager.kt` | 接收 server 配置，解析本地/远端 jar，校验 md5，懒加载 SPI 编译器 |
| `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/ICompilerCreator.kt` | SPI 入口，为当前 `ICompileContext` 和 `Disposable` 创建一个 `ICompiler` |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | 根据 `CompileOrder` 在内置阶段前后执行自定义编译器 |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | 定义 `before/after asset/res/source/minify/dex` 与 `atFirst/atLast` 插入区间 |
| `CompileUiHandler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt` | 编译侧交互抽象，屏蔽 IDE UI、CLI 默认实现和 androidTest 事件 sink |
| `CustomCompilerInfo` | `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt` | server 下发的 jar 名、路径和 md5 配置模型 |
| `Example*CustomCompiler` | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | 示例 SPI 实现，覆盖 assemble、delay、hook init 等常见插入形态 |

---

## 3. 核心数据模型

| 对象 | 来源 | 关键语义 |
|------|------|----------|
| `CustomCompilerInfo.jarFileName` | server config | 远端 jar 下载到 `customCompilerDir` 时使用的文件名 |
| `CustomCompilerInfo.path` | server config | 可以是绝对路径、相对 `projectDir` 路径、或 `http(s)` URL |
| `CustomCompilerInfo.md5` | server config | 本地已有 jar 必须匹配；远端下载后也必须匹配，不匹配会删除 |
| `customCompilerJars` | `CustomCompilerManager` 内存状态 | 当前有效 jar 列表；废弃 jar 会从 `customCompilerDir` 清理 |
| `customCompilers` | `CustomCompilerManager` 懒加载缓存 | 首次 `getCustomCompilers()` 时通过 `ServiceLoader` 创建；每批 compiler 注册到 manager 内部的 `Disposable` compatibility scope，配置/jar 列表变化、下载完成或 manager `close()` 时先释放旧实例，再关闭旧 classloader |
| `ICompiler.order` | 自定义编译器实现 | 决定被哪个 `BaseCompiler` 的 before/after hook 执行 |

---

## 4. 装载与执行链路

### 4.1 配置到 jar 状态

`ProjectCustomConfigManager` 将 local/server custom config 统一交给 `CustomCompilerManager` 维护 jar 状态。这里不需要记住单文件内的方法顺序，只需要关注四个规则：

- `null` config 不清空旧状态；非 null 列表才会重算有效 jar。
- 本地 jar 必须存在且 md5 匹配才进入 `customCompilerJars`。
- 远端 jar 先复用缓存；缓存不存在时后台下载，下载后校验 md5。
- 下载成功、配置或显式 jar 列表变化后会清空已创建的 `customCompilers` 并关闭旧 `URLClassLoader`，下次 `getCustomCompilers()` 再懒加载 SPI；manager `close()` 也会释放 loader。
- `CustomCompilerManager` 对外实现 `AutoCloseable`，初始化只接收 `ICompileContext`；`Disposable` 仅保留为 `ICompilerCreator` SPI 的内部兼容 scope。
- 运行期 custom config 应用进入项目写锁，避免正在编译时释放旧 compiler scope 或 classloader。

### 4.2 SPI 实例到编译阶段

```text
BaseCompileContext.customCompilers
  -> CustomCompilerManager.getCustomCompilers()
     -> URLClassLoader(customCompilerJars, current classloader)
     -> ServiceLoader.load(ICompilerCreator)
     -> creator.create(context, parent)
  -> BaseCompiler.compile(task)
     -> executeBeforeCustomCompilers(beforeCompileOrderRange, task)
        -> consumeFiles() 先过滤后续输入
        -> compile(filteredTask)
     -> 内置 doCompile(filteredTask)
     -> executeAfterCustomCompilers(afterCompileOrderRange, filteredTask, result)
        -> 把内置 outputs 转回 CompileFile 后交给自定义编译器
```

---

## 5. 编译交互协议

`CompileUiHandler` 是编译流程唯一应该依赖的交互面。IDE、CLI、测试默认实现都通过它提供行为，编译核心不直接操作具体 UI。

| 对象 | 文件 | 用途 |
|------|------|------|
| `isForceGradleCompile` | `CompileUiHandler` | 用户强制 Gradle 编译开关 |
| `isSkipDeploy` / `isAlwaysRestartApp` | `CompileUiHandler` | 编译后的部署策略输入 |
| `createCompileStatusHolder()` | `CompileUiHandler` | 创建取消与当前文件状态对象 |
| `createOutputParser()` | `CompileUiHandler` | Gradle 编译输出解析入口 |
| `confirmBuildChanges()` / `confirmDependencyChanges()` | `CompileUiHandler` | 构建文件/依赖变化时的用户确认 |
| `notifyByBalloon()` / `updateIndicatorText()` | `CompileUiHandler` | 用户可见进度提示 |
| `testEventSinkFactory` | `CompileUiHandler` | androidTest 运行时把 instrumentation 事件接到 Test Results |
| `RunResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/RunResult.kt` | 编译/部署终态描述 |
| `BuildChangesConfirmResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/BuildChangesConfirmResult.kt` | 构建变更确认结果 |

---

## 6. 隐形约束 / 设计思路

- `updateCustomCompilers(null)` 不会清空旧配置；只有收到非 null 列表才会重算 jar 并清理废弃缓存。
- 远端 jar 下载是异步的。首次更新配置后，如果 jar 尚未存在，本轮 `getCustomCompilers()` 可能仍为空；下载成功后 `resetCompilerJars()` 会让下一轮重新加载。
- 自定义编译器异常会被 `BaseCompiler` 捕获，打印用户可见 warn，并把当前 task 收口为失败；异常不会穿透到 IDE 进程。
- before hook 可以通过 `consumeFiles()` 改写后续内置编译输入；after hook 只能看到内置产物转成的 `CompileFile`。
- `order` 必须落在具体编译器暴露的区间内才会执行。例如想处理 Java/Kotlin 编译后的产物，应使用 `CompileOrder.afterSource`，并确认目标阶段由 `JavaCompiler` / `KotlinCompiler` / `SourceCompiler` 暴露。
- `URLClassLoader` 的 parent 是 Jugg 当前 classloader；自定义 jar 可以复用 Jugg API，但要避免打包冲突版本导致类加载行为不可预期。
- `CompileUiHandler.DEFAULT` 是无 UI 的安全默认值，适合 CLI/测试，但不会弹确认或展示 Run 窗口。

---

## 7. 新增自定义编译器建议步骤

1. 在独立模块实现 `ICompilerCreator` 与自定义 `ICompiler`。
2. 配置 `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator`。
3. 为自定义 `ICompiler.order` 选择明确的 `CompileOrder` 区间。
4. 在 server 配置中声明 jar 路径与 md5。
5. 通过 `CustomCompilerManager` 更新并检查日志中的 jar 解析、下载和 `initCompilers finished`。

---

## 8. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 配置了 jar 但没生效 | `CustomCompilerManager.updateCustomCompiler()`，检查路径类型与 md5 |
| 远端 jar 下载成功但本轮没执行 | `downloadCompilers()` / `resetCompilerJars()`，确认是否需要下一轮 compile 重新加载 |
| `ServiceLoader` 没找到实现 | jar 内 `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator` |
| 编译器执行阶段不对 | `ICompiler.order` 与 `CompileOrder` 区间，目标内置编译器的 `beforeCompileOrderRange` / `afterCompileOrderRange` |
| 自定义编译器失败导致整轮失败 | `BaseCompiler.executeBeforeCustomCompilers()` / `executeAfterCustomCompilers()` warn 日志 |
| UI 确认或取消行为与预期不一致 | 当前 `CompileUiHandler` 实现，而不是 `CompileUiHandler.DEFAULT` |

---

## 9. 关联文档

- 编译核心：`02_compile_core.md`
- IDE 执行链：`04_engineering_ide.md`
- 工程/配置：`04_engineering_project.md`
