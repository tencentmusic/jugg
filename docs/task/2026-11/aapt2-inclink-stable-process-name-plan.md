# aapt2-inclink 稳定进程名

## 已确认方案

用户确认：固定文件名，每次插件启动后首次使用时直接覆盖，由 warm-up 的 load table 顺带完成。无需 SHA-256、版本 marker 或软链接。

## 实施范围

- 三平台内置资源改名为 `tools/{darwin,linux}/aapt2-inclink` 和 `tools/windows/aapt2-inclink.exe`。
- `Aapt2DaemonInvoker` 在 companion 中使用线程安全 lazy 初始化路径：每个插件 classloader 生命周期首次使用时无条件复制一次，后续 APK、多个工程和 daemon 重建复用路径。
- 沿用 `ArscCompiler.warmUp()` → `loadTable()` → invoker 的调用链；没有可预热 APK 时延迟到真实首次使用。
- 在目标同目录写完整临时文件、设置执行权限后替换目标，避免直接截断正在执行的 Unix 二进制。异常向上传递，临时文件在 finally 清理。
- 通用 `copyResource()` 和资源编译协议保持原行为。
- 同步 `02_compile_resource.md`；现有 Wiki 已使用稳定名称，无需修改。

## 验证

- L1 owner `EmbeddedAapt2Test`：预置过期文件后验证首次调用覆盖为内置内容、basename 固定、重复调用不再复制。
- 定向执行已有 `ResourceCompileTest` 验证 compile/load/link 链路。
- 构建 aapt2 资源 JAR 并检查三平台名称；编译验证和 `git diff --check`。
- 当前机器只验证 macOS 实际运行；Windows 文件被其他 IDE 的 daemon 占用时替换可能失败，保留异常，需要退出占用进程后再次初始化，不引入带版本号的备用入口。

## 执行结果

- 失败证据：改动前新增测试因返回文件名携带 `2.19.16` 失败。
- L1 `EmbeddedAapt2Test` 在独立 JVM、临时 user.home 中验证过期缓存覆盖、固定 basename 和重复获取不复制，最终通过。
- JDK 17 + 本机 ANDROID_HOME 下，`ResourceCompileTest.compileArsc` 与 `compileResourceOverlay` 两条已有资源链回归通过。
- 初次资源回归缺少 ANDROID_HOME；补齐后 demo 的旧 D8 在 JDK 21 下崩溃，切到 JDK 17 后通过。未修改 demo 或工具链配置。
- `:main:compileKotlin`、`:aapt2-inclink:jar` 和三平台 JAR 条目检查通过；`git diff --check` 通过。
- 实现经只读审查，测试目录及 lazy 状态隔离问题已修复并复查通过。
- Linux/Windows 实机运行未验证。
