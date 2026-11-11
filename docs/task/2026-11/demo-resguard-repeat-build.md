# Demo ResGuard 重复构建与 Windows 验证

## 问题与方案

资源回归测试的 `bundleReleaseToApk` 前置构建重复执行时，ResGuard 因已有 `base-duplicated.txt` 失败。旧脚本清理的是 `app/build`，实际集中式输出位于 `build/app`。仅在 `aabresguardRelease` 执行前删除该插件生成的重复资源报告，保留其他构建结果。

Windows 没有 `unzip` 时，APK 集提取失败。改用 Java `ZipFile` 直接提取 `universal.apk`，缺少 entry 时明确失败，流与归档均关闭。

目录资源测试的旧期望为 33，当前实际为 35 个文件和一份 `resources.arsc`。现场存在 drawable-v24 向量及其 `$ic_launcher_foreground__0.xml` 梯度资源；修正既有数量断言为 35，不修改生产资源过滤逻辑。

## 验证

- 失败证据：`base-duplicated.txt already exists`、Windows 缺少 `unzip`、目录 overlay 期望 33 实际 35。
- 复用 `ResourceCompileTest` 与 `ResourceCompileAabResGuardTest`，不为简单构建脚本新增实现细节测试。
- 最终使用 `:main:test --tests '*ResourceCompileTest' --tests '*ResourceCompileAabResGuardTest' --tests '*EmbeddedAapt2Test' --max-workers=1` 定向运行，32 个用例全部通过，无失败、错误或跳过；ResGuard APK 集与 universal APK 成功生成，`git diff --check` 通过。
- 一轮启动出现 Windows `CreateProcess error=32`，单类串行执行也复现；不能归因于两个类并行。检查无残留进程后缓存二进制可直接启动，再执行完整回归确认。
- 完整回归再次复现 error 32 后，仅在 Windows 的该启动错误处等待一秒并重试一次，其余错误和最终异常保持抛出。不新增 mock 专用 seam；以现有真实进程资源回归和缓存刷新测试验证启动结果。

## 文档边界

仅测试夹具构建与断言维护，无插件运行时能力或架构变更，不影响 Wiki 使用说明。
