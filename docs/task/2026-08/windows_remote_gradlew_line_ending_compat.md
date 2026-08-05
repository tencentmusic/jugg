# Windows 远程编译 gradlew 换行兼容方案

## 1. 背景

Windows 工作区中的 `gradlew` 可能使用 CRLF。文件同步到远端 Linux 后，shebang 会被解析为 `sh\r`，导致 `/usr/bin/env` 返回 127，Gradle 尚未启动即失败。

报告 `53688f32` 已稳定复现该问题：远端执行 `./gradlew` 后输出 `/usr/bin/env: sh\r: No such file or directory`。

## 2. 已批准范围

- 仅在本机 Windows、启用远程编译且本轮实际执行远程构建时处理。
- 复用 `GradleWrapperRepairer` 定位 `compileCommand` 使用的 Unix `gradlew`。
- 在远程同步前，将本地 `gradlew` 中的 CRLF 转换为 LF。
- 仅在内容实际变化时写回并打印用户可见日志。
- 支持项目根目录和子目录 wrapper，例如 `./gradlew`、`android/gradlew`。
- 保留现有缺失 Gradle Wrapper 文件补齐行为。

## 3. 非目标

- 不转换 `gradlew.bat`、`build.gradle`、`settings.gradle`、源码或其他 shell 脚本。
- 不新增配置开关、确认弹窗或通用文本换行框架。
- 不处理项目外 wrapper、绝对路径或无法识别的自定义命令。
- 不修改远端文件，不自动提交本地 `gradlew` 变化。

## 4. 实现方案

### 4.1 调用条件

`JuggCompileHelper.gradleCompile()` 调用 `GradleWrapperRepairer` 时传入：

```text
isWindows && effectiveOptions.isRemoteCompile && !isOnlyFetchResult
```

### 4.2 Wrapper 修复

`GradleWrapperRepairer` 保持单一 wrapper 修复 owner：

1. 解析 compile command 中的 wrapper executable。
2. 保持现有缺失 wrapper 文件补齐逻辑。
3. 仅当调用方允许且 executable 为 Unix `gradlew` 时读取文件字节。
4. 仅将 `\r\n` 转换为 `\n`，保留孤立 `\r`。
5. 内容无变化时不写文件；有变化时覆盖原文件并记录路径。
6. 写入失败时保留异常并终止本次构建，避免同步已知不可用的 wrapper。

`GradleWrapperRepairResult.Repaired` 同时表示缺失文件补齐或换行修复，不新增结果类型。

## 5. 测试与验证

在 `GradleWrapperRepairerTest` 增加 L1 行为测试：

- CRLF `gradlew` 在允许转换时变为 LF。
- 已是 LF 时不写入并返回 `Skipped`。
- 未允许转换时保持 CRLF。
- 混合换行仅转换 CRLF，保留孤立 CR。
- 子目录 wrapper 正确处理。
- `gradlew.bat` 和非 wrapper 命令不转换。
- wrapper properties 不存在时维持现有跳过行为。

定向验证：

```bash
./gradlew :main:test --tests "*GradleWrapperRepairerTest*"
./gradlew :idea:compileKotlin
```

真实环境替代验证：Windows 工作区将 `gradlew` 设置为 CRLF，执行远程 Linux 构建，确认本地文件转换为 LF、远端 Gradle 正常启动，第二次构建不重复写入。

## 6. 文档同步

- 更新 `docs/ai_knowledge/04_engineering_project.md` 的 Gradle Wrapper 修复行为。
- 更新 `docs/ai_knowledge/98_code_map.md` 的 Gradle 编译客户端描述。
