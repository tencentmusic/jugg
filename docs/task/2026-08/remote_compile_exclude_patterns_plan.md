# Remote compile exclude patterns 默认值方案

## 背景

Remote compile 当前在 rsync 命令中隐藏追加 `build/`、项目锚定的 `.gradle/**`、`local.properties`、`.idea/`、`*.iml`、`.git/objects/`、`.git/modules/`、`.cxx/` 等固定排除规则。Run Configuration 的 Additional exclude patterns 只能继续追加规则，用户无法看到或移除其中适合开放的默认排除项。

`.gradle` 和 `build` 同时承载 Jugg 必需文件，并依赖固定 include/exclude 顺序保护 `.gradle/jugg` 和 `build/jugg`。为保持已有用户的 rsync filter 顺序和实际同步结果，本次继续固定排除这两个目录，只公开其余默认规则。

## 已批准行为

- 将 Additional exclude patterns 改为 Exclude patterns，控制除 `.gradle` 和 `build` 之外的可配置排除规则。
- `.gradle` 和 `build` 保持原有固定 include/exclude 规则及参数顺序，不允许通过界面移除。
- 未自定义时，界面显示并执行 `local.properties`、`.idea/`、`*.iml`、`.git/objects/`、`.git/modules/`、`.cxx/`。
- 用户修改后，界面中的列表成为唯一的可配置排除列表。
- 用户可以清空列表，表示不应用可配置排除规则；固定 `.gradle` 和 `build` 规则仍然生效。
- 不兼容老版本已填写的 Additional exclude patterns；旧配置没有新自定义标记时统一按未自定义处理。
- `build/jugg`、`.gradle/jugg` 等 Jugg 必需文件仍由原有内部 include 规则保护。
- 删除 `.git/objects/` 等目录规则会放开整个目录；本次不提供单文件 include exception。

## 状态与兼容规则

新增 `isRemoteSyncExcludePatternsCustomized`，默认值为 `false`：

```text
customized = false -> 显示并执行默认列表
customized = true  -> 显示并执行用户列表，允许为空
```

保存设置时，若输入列表与默认列表一致，则保存为未自定义；否则保存为已自定义。布尔标记用于区分“未设置”和“明确清空”，因为 JetBrains `BaseState.string()` 会把空字符串归一化为 `null`。

## 修改范围

### main

- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptions.kt`
  - 提供固定的可配置默认排除列表。
  - 保存自定义标记并提供最终生效列表。
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/SshCommand.kt`
  - 恢复 `.gradle`、`build` 的原有固定路径替换、排除规则和参数顺序。
  - 其余排除规则只使用调用方传入的最终列表。
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteGradleCompileClient.kt`
  - 向 IFT 和 rsync 同步命令传递最终生效列表。
- `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt`
  - 恢复内部 `build` 和 `.gradle` exclude，完整保留原有 Jugg 必需 include/exclude 顺序。
- `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/RunConfigurationTemplate.kt`
  - 追加自定义标记，缺失字段按 `false` 兼容。
- `main/src/main/java/com/sickworm/intellij/jugg/server/RunConfigurationTemplateExt.kt`
  - 在默认配置模板中保留原始列表和自定义状态。

### idea

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfigurationOptions.kt`
  - 在属性末尾追加自定义标记。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunConfigurationOptionsExt.kt`
  - 转换和复制排除列表及自定义状态。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponent.kt`
  - 重命名为 Exclude patterns。
  - 未自定义时显示固定的可配置默认列表。
  - 按输入是否等于默认列表保存自定义状态，支持明确清空。
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
  - 恢复无项目路径参数的设置组件创建方式。

### 测试与文档

- 扩展 `SyncFileCommandTest`、`JuggGradleCompileOptionsTest`、`JuggRunSettingsComponentTest`，不新增测试 owner。
- 更新 `docs/ai_knowledge/05_utilities.md`。
- 更新 `docs/wiki/zh/guide/remote-gradle.md` 和 `docs/wiki/zh/troubleshooting/remote-gradle.md`。

## 验证

- TDD：先确认默认可配置规则可见、明确清空后固定规则仍生效，以及默认命令 filter 顺序与老版本一致。
- 定向执行 main 和 idea 测试，不执行无过滤全量测试。
- 执行 `./gradlew :idea:compileKotlin`。
- 使用本地 rsync dry-run 验证默认 filter 与老版本同步结果一致，并验证移除 `.git/objects/` 后目录会进入同步结果。
- 执行 Wiki production build、产物检查和 `git diff --check`。

## 非目标

- 不兼容老版本已填写的 Additional exclude patterns。
- 不提供 include exception、否定规则或单文件白名单语义。
- 不开放 `.gradle` 和 `build` 排除规则，不动态识别 Gradle 自定义 build directory 作为上传排除项。
- 不修改远端产物拉取过滤规则。
- 不使用 `--delete-excluded` 清理远端已有排除文件。
