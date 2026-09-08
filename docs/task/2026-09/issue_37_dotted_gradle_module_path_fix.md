# Issue #37 Gradle 模块名含点号的运行配置修复方案

> 状态：方案 / 待实现
> 关联 Issue：https://github.com/tencentmusic/jugg/issues/37
> 关联报告：`c82381c3`

## 1. 用户可见问题

工程存在合法 Gradle 模块路径：

```text
:zxphone5.0
```

Android Studio 已正确识别对应构建任务：

```text
:zxphone5.0:assembleGooglePlayDev
```

Jugg 3.4.2 自动生成 Run Configuration 时，将模块名中的所有 `.` 都解释为 Gradle 层级分隔符，生成：

```text
./gradlew :zxphone5:0:assembleGooglePlayDev
```

Gradle 因而把 `0` 当作 `:zxphone5.0` 下不存在的子项目，构建在执行 assemble 前失败：

```text
Cannot locate tasks that match ':zxphone5:0:assembleGooglePlayDev'
as project '0' not found in project ':zxphone5.0'.
```

## 2. 根因与行为边界

`SuggestRunConfiguration` 当前把两个不同概念编码在同一个 `moduleName` 字符串中：

1. Jugg 配置身份，例如 `app`、`SMCommon.app`、`zxphone5.0`；
2. Gradle project path，例如 `:app`、`:SMCommon:app`、`:zxphone5.0`。

`createCompileCommand()` 再通过 `moduleName.replace('.', ':')` 还原 Gradle path。该转换不可逆，无法区分：

| Jugg moduleName | 真实含义 | 当前转换结果 |
|---|---|---|
| `feature.app` | 多层模块 `:feature:app` | `:feature:app` |
| `zxphone5.0` | 单层模块 `:zxphone5.0` | `:zxphone5:0` |
| `SMCommon.app` | included build `:SMCommon:app` | `:SMCommon:app` |

同一假设还存在于 `JuggManager.generatedVariant()`：它也通过替换 `moduleName` 中的点号来判断命令是否属于当前模块。若只修 `createCompileCommand()`，含点号模块虽然能够生成正确命令，但 Active Build Variant 自动切换仍会把该命令判为非标准配置。

## 3. 推荐方案

### 3.1 分离配置身份与 Gradle task path

保留现有 `moduleName` 语义，只用于：

- Jugg Run Configuration 命名；
- 同模块 suggestion 匹配；
- composite build 下的配置身份区分。

新增集中式 Gradle module path 解析能力，直接消费 Android Studio `GradleProjectPath` 提供的原始 `path` 与 `buildRoot`：

- root build：原样保留 `gradleProjectPath`，例如 `:zxphone5.0`、`:feature:app`；
- included build：在原始 path 前增加 included build identity，例如 `buildName=SMCommon`、`path=:app` 生成 `:SMCommon:app`；
- 不对任一 Gradle path segment 内的 `.` 做替换。

Gradle API、反射或数据读取失败时，继续使用现有 IDE module name 规则作为 Best-effort fallback。fallback 只保证旧版 Android Studio 行为不退化；由于 IDE 展示名本身没有保存点号的来源语义，fallback 无法可靠恢复“模块层级”和“名称内点号”的区别，不额外猜测。

### 3.2 Compile Command 只拼接已解析的原始 path

调整 `SuggestRunConfiguration.createCompileCommand()` 的输入契约：接收已经解析完成、以 `:` 开头的 Gradle module path，不再执行全局 `.` → `:` 转换。

示例：

```text
modulePath=:zxphone5.0, task=assembleGooglePlayDev
  -> ./gradlew :zxphone5.0:assembleGooglePlayDev

modulePath=:feature:app, task=assembleDebug
  -> ./gradlew :feature:app:assembleDebug

modulePath=:SMCommon:app, task=assembleDebug
  -> ./gradlew :SMCommon:app:assembleDebug
```

`v_chipmunk`、`v_narwhal_feature`、`v_quail` 三个 suggestion 生成入口统一调用该能力，不在各版本 compat 中复制路径转换规则。

### 3.3 Active Build Variant 从实际 task 判断模块

调整 `JuggManager` 的标准生成命令识别：

1. 仍只接受精确的单 task 命令 `./gradlew :modulePath:assemble{Variant}`；
2. 从命令本身解析 `modulePath` 和 `variant`，不再从 `moduleName` 反向重建 path；
3. selected configuration 与 active suggestion 的实际 `modulePath` 必须相同，才允许自动切换；
4. 带参数、多 task、脚本包装及非 assemble 自定义命令继续保持用户选择。

这样既支持 path segment 内的点号，也不放宽当前“只自动切换 Jugg 自动生成配置”的安全边界。

## 4. 预计改动

| 文件 | 预计改动 |
|---|---|
| `deploy_compat/interface/.../SuggestRunConfiguration.kt` | 分离 module identity 与原始 Gradle module path；Compile Command 不再替换点号；保留旧 IDE fallback |
| `deploy_compat/v_chipmunk/.../ChipmunkAsDeployerCompat.kt` | 使用集中式原始 Gradle path 生成 suggestion command |
| `deploy_compat/v_narwhal_feature/.../NarwhalAsDeployerFeatureCompat.kt` | 同上 |
| `deploy_compat/v_quail/.../QuailAsDeployerCompat.kt` | 同上 |
| `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 从实际单 task command 解析 module path 与 variant，保持自定义命令不自动切换 |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggManagerRunConfigurationSyncTest.kt` | 增加点号模块、nested module、included build、fallback 与 variant 切换回归 |
| `docs/ai_knowledge/04_engineering_ide.md` | 记录配置身份与 Gradle path 分离后的运行配置语义 |
| `docs/ai_knowledge/04_engineering_compat.md` | 记录原始 Gradle path 保真和 fallback 边界 |
| `docs/wiki/zh/guide/run-configuration.md`、英文镜像 | 补充模块名含点号时自动生成任务仍保留原始 Gradle path |

不修改 Gradle 编译客户端、项目构建脚本、APK 输出路径、已有用户自定义 Run Configuration 或完整构建基线格式。

## 5. 测试价值与 TDD 落点

该行为通过测试价值门禁：自动生成的 Gradle task 是用户可见、稳定且已被真实破坏的外部命名契约，能够通过确定性字符串和配置选择结果持续判定。

实现前先在现有 owner `JuggManagerRunConfigurationSyncTest` 中增加失败用例：

1. root build `:zxphone5.0` 生成 `./gradlew :zxphone5.0:assembleGooglePlayDev`；
2. 普通 nested module `:feature:app` 继续生成正确命令；
3. included build `SMCommon + :app` 继续生成 `:SMCommon:app`；
4. Gradle path 中任一 segment 含点号时不被拆分；
5. Gradle identity 不可用时，legacy fallback 行为保持不变；
6. 含点号模块从 debug 切换到 dev variant 时，能够创建并选择正确配置；
7. 带参数、多 task、非 assemble 等自定义命令仍不自动切换。

测试层级：

- 路径和命令生成属于确定性命名契约，按 L1 断言；
- Sync 后创建/选择配置属于 IDE 编排行为，复用现有 `JuggManagerRunConfigurationSyncTest` 的 L2 owner；
- 本次不改变编译或部署编排，不要求新增 L3 Flow。

## 6. 验证计划

### 自动化验证

```text
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.JuggManagerRunConfigurationSyncTest"
```

### 编译与兼容验证

1. 编译 `deploy_compat/interface` 及受影响的 Chipmunk、Narwhal Feature、Quail compat 模块；
2. 执行 `./gradlew :idea:compileKotlin`；
3. 检查 compat 接口未新增版本专属 Android Studio 类型，保持反射失败可回退；
4. 检查本次 diff 中没有新的 `moduleName.replace('.', ':')` 或同义反向推导。

### 场景矩阵

| 场景 | 期望结果 |
|---|---|
| `:app` | 保持 `:app:assembleDebug` |
| `:feature:app` | 保持多层 project path |
| `:zxphone5.0` | 点号作为模块名字符保留 |
| `:feature.api:app` | 只保留 segment 内点号，不改变层级冒号 |
| included build `SMCommon + :app` | 保持 `:SMCommon:app` |
| included build path segment 含点号 | build identity、层级和点号均保留 |
| GradleProjectPath API 不可用 | 回退旧 IDE module name 解析，不阻断配置创建 |
| 自定义命令 | 不因 Active Build Variant 改变被自动替换 |

## 7. 反证与风险控制

最强竞争解释是用户手工填错 Compile Command，但报告日志在用户运行前已经记录 Jugg suggestion 为 `:zxphone5:0:assembleGooglePlayDebug`，后续 dev 配置与实际执行命令沿用相同错误路径，可排除手工输入是根因。

风险主要集中在两点：

1. **Composite build 回归**：修复不能简单地停止所有点号替换，否则 `SMCommon.app` 会退化为不存在的单 segment；必须使用 Android Studio 提供的原始 project path 和 build identity。
2. **自动切换误伤自定义命令**：不能只按 task 名后缀提取 variant；必须同时要求精确单 task 格式，并比较 selected command 与 active suggestion 的实际 module path。

## 8. 临时规避

修复发布前，用户可在 Jugg Run Configuration 中手工将 Compile Command 改为：

```text
./gradlew :zxphone5.0:assembleGooglePlayDev
```

Output APK name 保持现有：

```text
zxphone5.0/build/outputs/apk/googlePlay/dev/*.apk
```
