# Gradle 回退时清理构建输出

## 问题与范围

报告 `395c9a28` 的 14:44 构建已带 `--no-build-cache --rerun-tasks`，但 `compileMidl` 内部仍输出 `MIDL: All files are up to date`，随后编译异常的旧生成源码。用户选择在现有清缓存回退选项中增加 Gradle `clean`。

仅修改勾选 `clean gradle cache on fallback` 后的命令派生：在 Gradle 可执行文件后加入 `clean`，先于原任务执行，保留原任务和参数；已有 `clean` / `:clean` 时不重复加入。继续禁用 build cache 并强制任务执行，设置仍只影响本轮。普通回退保持原行为。清理范围由工程自身的 `clean` task 决定，不承诺删除该范围外的生成器缓存。

## 验证

复用 L1 `JuggGradleCompileOptionsTest`，保护对外 Gradle 命令的任务顺序、参数保留和重复处理行为；不增加生产测试 seam。现场 MIDL 工程未在本机提供，无法验证该工程清理后的实际结果。

标准定向测试目前被存量 `LibraryTestApkBuildHistoryTest.FakeGitManager` 未实现接口方法阻塞；使用临时 Gradle init script 排除该存量失败测试后运行当前测试 owner，避免修改无关测试。

已确认新增断言在修改生产代码前失败（缺少 `clean`）。修改后通过 `JuggGradleCompileOptionsTest` 25 项测试（另 1 项按原条件跳过），覆盖清理先于构建、已有 clean/:clean、Windows wrapper、带空格路径、已有 bypass 参数、重复处理及原配置不变。使用 JDK 17 和 `/tmp` 下临时 init script 排除存量编译失败测试；未修改无关测试。Wiki production build 与 `git diff --check` 通过。

## 文档

同步 `02_compile_core.md`，以及 Wiki 的中英文“降级 Gradle 编译”页，说明选项的清理范围、本轮生效和耗时边界。
