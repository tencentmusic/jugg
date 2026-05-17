# Library Test APK 构建记录回放方案

> 创建时间：2026-05-17  
> 状态：方案确认，待落地  
> 适用范围：只处理 Jugg AndroidTest 目标下 library-style self-targeting Test APK 的构建记录、回放注入与 APK 收集，不改变普通 APP target 行为。  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 背景

当前 Jugg 已支持 library-style self-targeting Test APK 缺失时的懒加载补齐：

1. `sourcePath` 唯一命中某个 `.androidTest` synthetic module。
2. 当前 APK 列表中找不到该 module 对应的 self-targeting Test APK。
3. `LibraryTestApkBackfillHelper` 派生并执行 `:<module>:assemble<Variant>AndroidTest`。
4. Gradle 构建成功后解析新增 Test APK，合入本轮 APK 列表并安装。

这个流程能解决当前一次运行的 Test APK 缺失问题，但下一次普通 Jugg AndroidTest Gradle Build 不会主动构建最近用过的 library Test APK。后续再次运行这些 library androidTest 时，仍可能重新进入缺失补齐流程，浪费时间。

目标是在 backfill 成功后持久化记录，并在下一次 Jugg `BuildTarget.ANDROID_TEST` Gradle Build 中回放最近的 library Test APK 构建任务，提前产出 Test APK。

---

## 2. 目标

- backfill 成功后记录 library module 的构建命令、构建时间和 APK 路径。
- 记录跨同一 git 仓库的不同 checkout / worktree 共享。
- 下次 Jugg AndroidTest Gradle Build 时，最多回放最近 3 条有效 library Test APK 构建记录。
- 回放与 application androidTest task 的注入位置保持一致，避免 compile helper 里散落 task 拼接逻辑。
- 历史 library Test APK 预热失败不影响 application androidTest baseline 成功。

---

## 3. 非目标

- 不影响 `BuildTarget.APP`。
- 不拦截 Android Studio / IntelliJ Gradle 面板中的任意 Gradle task。
- 不改变用户 RunConfig 中的 compile command 或 output APK 配置。
- 不为 library Test APK 引入新的运行 target。
- 不把该记录混入 `FullBuildInfo`。

---

## 4. 持久化设计

### 4.1 文件位置

新增独立 history 文件：

```text
~/.jugg/library_test_build_records/{projectName}_hash{0:8}.json
```

`projectName`：

1. 优先使用 git project name。
2. 兜底使用工程目录名。

hash 输入：

1. git 工程优先使用 `remote.origin.url`。
2. 没有 origin 时使用第一个 remote URL。
3. 非 git 工程使用工程绝对路径。
4. hash 前只做 `trim`，不做 URL 归一化。

这样同一个 git 仓库的多个 checkout / worktree 可以共享记录；非 git 工程按路径隔离。

### 4.2 JSON 结构

新增 `LibraryTestApkBuildHistory`：

```json
{
  "version": 1,
  "projectKey": "git@host:group/repo.git",
  "updatedAt": 1770000000000,
  "records": [
    {
      "moduleName": "library1.androidTest",
      "buildVariant": "debugAndroidTest",
      "compileCommand": "./gradlew :library1:assembleDebugAndroidTest",
      "compiledAt": 1770000000000,
      "apkPath": "/path/to/library1-debug-androidTest.apk",
      "outputApkPattern": "library1/build/outputs/apk/androidTest/debug/*.apk"
    }
  ]
}
```

写入时按 `moduleName + buildVariant` upsert。history 文件不裁剪总条数，方便后续追溯“没有构建过”还是“不在最近 3 条/已过期”。

---

## 5. 写入时机

记录写入发生在 `LibraryTestApkBackfillHelper` 成功完成后：

1. Gradle task 执行成功。
2. APK 解析成功。
3. 新 APK 合入本轮 APK 列表。
4. backfilled APK 安装成功。
5. `compileContextManager.updateApkInfos(...)` 完成。

只有完成上述步骤后才记录，保证记录代表“编译成功 + APK 插入成功”的可用 baseline。

---

## 6. 回放与注入

### 6.1 生效条件

只在 Jugg `BuildTarget.ANDROID_TEST` Gradle Build 生效。`BuildTarget.APP` 不参与。

原因：

- `ANDROID_TEST` 是用户开启 incremental Android Test 后的目标。
- application Test APK 由现有 `BuildTarget.ANDROID_TEST` 逻辑构建。
- library Test APK backfill 不属于 application Test APK 构建目标，需要额外补充。

### 6.2 记录过滤

每次 AndroidTest Gradle Build 从 history 中选择有效记录：

1. `compiledAt` 在最近 30 天内。
2. 当前工程 project info 中仍存在对应 `moduleName`。
3. 当前推断 variant 匹配 `record.buildVariant`。
4. Gradle task 未在本次请求中重复出现。
5. 按 `compiledAt` 倒序最多取 3 条。

variant 推断复用 application androidTest task 注入处的逻辑。library 记录只在 `record.buildVariant == "${variant}AndroidTest"` 时参与回放。

### 6.3 task 注入位置

task 注入与 application Test APK 保持一致，放在 init gradle script 的 `injectAndroidTestTaskIfNeeded()` 同一层：

- application Test APK：把 `:app:assemble<Variant>AndroidTest` 挂到 requested tasks 的 `dependsOn`。
- library Test APK：把历史记录中的 `:<lib>:assemble<Variant>AndroidTest` 也挂到同一批 requested tasks 的 `dependsOn`。

日志也放在同一处，沿用类似输出：

```text
Jugg: inject :library1:assembleDebugAndroidTest before :app:assembleDebug
```

---

## 7. APK 收集

application Test APK 当前由 `LocalGradleCompileClient` / `RemoteGradleCompileClient` 在 `BuildTarget.ANDROID_TEST` 下从 app APK 路径推导。

library Test APK 无法从 app APK 路径可靠推导，因此需要把选中的 history 记录里的 `outputApkPattern` 作为本次额外 APK 查找目标传给 Gradle compile client。

行为要求：

- 找到 APK：复制到 Jugg classpath APK 目录，并纳入本次 APK 解析结果。
- 找不到 APK：打印 warning，但不阻断本次 Gradle Build。
- Gradle task 本身失败：按正常 Gradle Build 失败处理，不自动降级重试。

---

## 8. 测试计划

### 8.1 history 持久化单元测试

- git 工程优先使用 `remote.origin.url` 生成记录文件名。
- 没有 origin 时使用第一个 remote URL。
- 非 git 工程使用工程绝对路径。
- 写入时按 `moduleName + buildVariant` upsert。
- deserialize 兼容缺失字段，返回空记录或安全默认值。

### 8.2 backfill 写入测试

- backfill 成功且安装成功后写入记录。
- Gradle 构建失败不写入。
- APK 解析失败不写入。
- 安装回调抛异常时不写入。

### 8.3 回放过滤测试

- 只在 `BuildTarget.ANDROID_TEST` 下返回候选记录。
- `BuildTarget.APP` 下不返回候选记录。
- 过滤 30 天外记录。
- 过滤当前工程不存在的 module。
- 过滤 variant 不匹配记录。
- 最近有效记录最多返回 3 条。

### 8.4 Gradle 注入测试

- application androidTest task 和 library Test APK history task 都注入到 requested tasks 前。
- 重复 task 不重复注入。
- 没有有效 history 时保持现有 application androidTest 行为。

### 8.5 APK 收集测试

- 额外 `outputApkPattern` 命中时纳入 compile output。
- 额外 `outputApkPattern` 缺失时只 warning，不失败。
- 仍保持 application Test APK 缺失时的现有失败语义。

---

## 9. 文档同步要求

落地后需要同步 `docs/ai_knowledge/06_android_test.md`：

- 在 Gradle full compile 小节补充 library Test APK history 回放。
- 在部署与 instrumentation 小节补充 backfill 成功后的记录写入。
- 在排查小节补充 history 文件位置与“最近 3 条 / 30 天 / variant”过滤规则。

如新增关键类，也需要同步 `docs/ai_knowledge/98_code_map.md`。
