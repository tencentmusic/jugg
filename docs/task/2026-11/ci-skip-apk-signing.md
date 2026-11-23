# CI 增量 APK 跳过签名

**Purpose** — Support CI pipelines with a separate signer.
**Decision** — Allow explicit signing deferral on incremental APK rewrites.
**Impact** — The pipeline owns signing and verification before installation.

## 场景与约定

Demo CI 的增量 runner 没有 keystore，最终 APK 由流水线下游统一重签。CI 原始日志显示增量编译成功，但 APK 更新因 `/root/.android/debug.keystore` 不可见而失败，随后走完整 Gradle 构建。

仅 `buildIncrementalApk` 接受严格布尔参数 `skipSign`，默认 `false`。为 `true` 时只对实际改写的 APK 跳过签名配置检查、签名和验签；保留 APK 改写、对齐和原子替换。产物必须由下游重签并验签才能交付。空变更直接导出基线的路径、完整 Gradle 回退和 IDE 签名不变。Demo CI 默认 `SKIP_SIGN = True`。

## 已批准的改动范围

- `cmd_line`：增量 `Params.kt`、`ParamsParser.kt` 和 `BuildIncrementalApkCommand.kt` 解析并传递选项。
- `main`：`IncrementalDeployHelper.kt` 和 `ApkFileModifier.kt` 支持输出待签名 APK；保留原有诊断。
- Android_Demo：`scripts/jugg_ci/build.py` 仅在有变更的增量分支传入选项。
- 测试：在 `CmdLineTest.kt` 用缺失 keystore 的基线副本验证待签名、对齐和增量内容；回归默认签名。
- 文档：更新 CLI Demo README、`04_engineering_compat.md`、中英文 `supported-packaging.md`；本地打包，不部署共享 CI 服务。

## 本地验证

- 原始 CI 日志是缺 keystore 的失败证据。本机有改动的测试工程在基线阶段遇到既有 D8 错误，未改动该工程；用 Git 归档导出的干净工程副本和 JDK 17 完成定向 CLI 回归。
- `CmdLineTest.buildIncrementalApkWithoutSigning` 和 `CmdLineTest.buildIncrementalApk` 通过。前者确认缺 keystore 时增量 DEX 已写入、APK 已对齐，初次验签失败而使用独立测试 key 重签后验签通过；后者确认默认产物无需重签即可通过验签。
- CLI ZIP 打包、Demo CI Python 语法、Wiki 镜像和生产构建通过；共享 CI 的实际增量构建与下游重签待上线后验证。
