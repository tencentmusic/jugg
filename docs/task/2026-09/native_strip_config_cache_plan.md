# Native strip 配置本地缓存方案

## 1. 背景

Jugg report `75046b19` 中，C++ 增量构建执行以下派生任务：

```text
:mp:dtmp:mergeDebugNativeLibs
:mp:appcommon:mergeDebugNativeLibs
:juggCollectExternalBuildInfo
```

工程开启 Gradle Configuration on Demand，APK owner `:app` 没有进入本轮 task graph，因此其 Android Gradle Plugin 未完成配置。`juggCollectExternalBuildInfo` 随后从 `:app` 查找 `stripDebugDebugSymbols` 时得到空结果并失败：

```text
App strip task stripDebugDebugSymbols was not found in :app
```

同一报告的完整 Gradle 构建曾成功执行 `:app:stripDebugDebugSymbols`，说明任务并非真实缺失，而是 Collector 读取了尚未配置的 APK owner。

当前 Collector 读取该 task 的目的仅是获取以下稳定配置事实，然后在 invocation 目录内复现 AGP 的单文件 strip 语义：

- `keepDebugSymbols`
- ABI 到 strip executable 的映射

因此不应为了读取配置而强制配置 APK owner，也不应把 app strip、app merge 或其它无关 native producer 加入增量 invocation task graph。

## 2. 目标

1. 完整 Gradle 构建时缓存 APK owner 当前 variant 的 `keepDebugSymbols` 与 ABI strip executable。
2. C++ 增量构建优先使用缓存，不依赖 APK owner 本轮是否被 Gradle 配置。
3. 缓存能够随 CI `buildGradleBase` 基线备份，并可在另一台同平台 CI Worker 上继续使用。
4. 保持当前 AGP 单文件 strip、`keepDebugSymbols`、缺少 ABI 工具时 package-as-is、文件大小校验和异常契约不变。
5. 缓存缺失、损坏或过期时局部降级，不使用不可信配置伪造成功。

## 3. 非目标

- 不把 strip 配置加入 `Variant`、`ModuleInfo`、`ExternalBuildInfo` 或 project info 序列化模型。
- 不缓存 Gradle task、`ndkHandlerInput`、`sdkBuildService`、input artifact provider 或 strip task 输出目录。
- 不执行 APK owner 的 `strip<Variant>DebugSymbols` task。
- 不为本修复引入数据库、公共缓存框架或通用工具链抽象。
- 不改变 Flutter external build 路径。

## 4. 缓存目录

缓存属于可迁移的完整构建工具链基线，不属于运行状态数据库，保存到：

```text
build/jugg/classpath/native_strip/
├── config.json
└── tools/
    └── <tool-fingerprint>/
        └── <strip-executable-name>
```

在 `LocalClasspathStoragePathManager` 中增加对应目录字段。不得把文件写入 `classpath/root`，避免进入 Java/Kotlin 编译 classpath。

CI 若使用选择性产物白名单，必须备份整个 `build/jugg/classpath/native_strip/`，不能只备份 `config.json`。

## 5. 缓存内容

`config.json` 使用单文件、多个 entry 的结构。每个 entry 由标准化后的 `moduleRootDir + variant` 唯一标识：

```json
{
  "entries": [
    {
      "moduleRootDir": "/workspace/app",
      "variant": "debug",
      "keepDebugSymbols": [
        "*/arm64-v8a/libkeep.so"
      ],
      "stripExecutables": {
        "arm64-v8a": {
          "sourcePath": "/opt/android-sdk/ndk/xxx/llvm-strip",
          "backupPath": "tools/<tool-fingerprint>/llvm-strip"
        }
      }
    }
  ]
}
```

约束：

- `moduleRootDir` 保存规范化绝对路径；工程移动后旧 entry 自然 miss，不做路径猜测。
- `variant` 使用 APK owner 的真实 build variant。
- `keepDebugSymbols` 空列表是有效配置，不能等同于读取失败。
- `stripExecutables` 按 ABI 保存；有效配置中没有某个 ABI 工具时，继续沿用 AGP package-as-is 语义。
- `sourcePath` 仅用于当前构建机降级与诊断。
- `backupPath` 必须是相对 `native_strip` 根目录的安全相对路径，禁止绝对路径和目录穿越。
- 相同 executable 应按规范化源路径或稳定文件摘要去重，避免不同 ABI、module 或 variant 重复备份同一二进制。
- 读取时若相同 `moduleRootDir + variant` 出现多条 entry，视为缓存损坏，不选择其中任意一条。

## 6. 写入流程

普通完整 Gradle project info 读取期间，在 Application 与 Dynamic Feature 已配置后读取当前 variant 的 `strip<Variant>DebugSymbols`：

1. 读取 `keepDebugSymbols`。
2. 读取 ABI 到 strip executable 的映射。
3. 将 executable 复制到 `native_strip/tools/`，保留文件属性；macOS/Linux 必须保证 executable bit，Windows 保留可执行文件后缀。
4. 先完成全部工具文件写入，再通过临时文件和原子替换发布 `config.json`。
5. 新配置发布成功后，Best-effort 清理不再被引用的旧工具；清理失败只记录 debug，不破坏已发布缓存。

缓存刷新以本次成功读取结果为准。当前完整 Gradle 已配置某个 owner、但 strip 配置读取失败时，不继续保留该 owner/variant 的旧 entry，避免 AGP、NDK 或构建脚本变化后复用过期工具。

External build Collector 模式不得覆盖完整构建缓存。

## 7. 读取与降级流程

C++ Collector 已通过 request 获得 APK owner 的 `moduleRootDir` 与 `variant`。`stripExternalNativeOutput` 按以下顺序取得配置：

1. 从 `config.json` 精确匹配唯一的 `moduleRootDir + variant` entry。
2. 优先使用存在、可读且可执行的 `backupPath`。
3. 备份文件不可用时，若 `sourcePath` 仍然存在且可执行，降级使用 `sourcePath`。
4. entry 缺失、JSON 损坏、字段非法、重复 entry 或已记录工具路径失效时，尝试从已配置的 APK owner task 实时读取一次。
5. APK owner 未配置或实时读取仍失败时，保留最终异常并提示执行完整 Gradle 构建刷新 strip cache。

只对已知 cache miss/invalid 场景降级一次，不重复相同读取。缓存读取失败不得阻止 Collector 处理其它不依赖该 entry 的信息，但当前 C++ request 缺少有效 strip 配置时必须失败，禁止回退部署 unstripped merge output。

## 8. CI 基线

`buildGradleBase` 产出的基线必须自包含：

- `config.json` 与其引用的 `tools/` 同时保存。
- 不依赖原 CI Worker 的 Android SDK/NDK 绝对路径。
- 基线复制到另一工作目录后，读取 `backupPath` 时以当前 `native_strip` 根目录解析。
- 若流水线只保存既有 `classpath/root`、`apk`、`libraries`、`embedded_apk` 白名单，需要同步加入 `native_strip`。
- 多组增量任务复制基线时，`native_strip` 与其它 `build/jugg/classpath` 内容一同复制。

## 9. 兼容性与清理

- 老基线没有 `native_strip/config.json` 时按 cache miss 处理，不提升 project info 或 compile context 版本。
- JSON 无法解析时保留原始异常用于 debug，运行路径局部降级实时读取。
- `Clear Jugg Build` 删除 `build/jugg` 时自然清除缓存，不增加独立清理入口。
- 远程 Gradle 当前不支持 external source 增量的既有边界保持不变；本缓存主要服务本地 IDE 与 CI cmd_line 基线。

## 10. 验证方案

本行为具有稳定、用户可观察的回归价值，新增自动化测试。

### 10.1 失败证据

- Jugg report `75046b19`：Configuration on Demand 下 library C++ task 成功配置，但 APK owner `:app` 未配置，Collector 查找 `stripDebugDebugSymbols` 失败。

### 10.2 L1 缓存测试

在现有 native strip 测试 owner 中覆盖：

- 使用 cache 时 owner 没有注册 strip task，仍能按 ABI 完成 strip。
- `keepDebugSymbols` 命中时复制原文件。
- `moduleRootDir + variant` 精确匹配，不跨 variant 复用。
- 重复 key、损坏 JSON、不安全 `backupPath`、备份工具缺失按 cache invalid 处理。
- `backupPath` 缺失但有效 `sourcePath` 存在时只降级一次。
- 工具备份保留可执行能力；相同工具不会重复备份。
- 原子发布后配置引用的工具全部存在。

### 10.3 真实 Gradle 回归

扩展真实 AGP fixture，覆盖：

- `org.gradle.configureondemand=true`。
- C++ task 位于 library module，APK owner 为未被请求的 `:app`。
- 首次完整 Gradle 构建生成 native strip cache。
- 后续 external invocation 不配置 `:app`，Collector 仍成功产出 stripped native output。
- task graph 不执行 `:app:stripDebugDebugSymbols`，也不额外执行未被选中的 APK owner merge task；被选中的 library merge task 依旧是本轮 external task。

### 10.4 CI 基线验证

- 执行定向 `buildGradleBase` 回归或等价 cmd_line 测试。
- 将基线复制到不同临时路径，移除或使原 `sourcePath` 不可用。
- 使用备份 executable 完成 strip，证明缓存不依赖原 Worker 路径。

### 10.5 编译验证

执行项目规范允许的定向测试，并至少执行：

```text
./gradlew :idea:compileKotlin
```

禁止无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。

## 11. 文档同步

实现完成后同步：

- `docs/ai_knowledge/02_compile_core.md`
- `docs/ai_knowledge/04_engineering_project.md`
- `docs/ai_knowledge/98_code_map.md`

检查 `docs/wiki` 的 C++/SO 增量编译与 CI 基线页面；存在受影响描述时同步中英文镜像。

## 12. 验收标准

1. report `75046b19` 对应的 Configuration on Demand 场景不再因 APK owner 未配置而报 strip task missing。
2. C++ 增量 invocation 不执行 APK owner strip task，也不为读取配置额外依赖 owner merge task；当 owner merge 本身就是 selected external task 时保持执行。
3. strip 结果继续遵循 AGP `keepDebugSymbols` 与 ABI tool 语义。
4. 本地完整构建生成自包含的 `build/jugg/classpath/native_strip` 缓存。
5. CI 基线换目录、原 NDK 路径不可用后仍能使用备份工具。
6. 缓存无效时明确失败或有界降级，不部署 unstripped merge output，不伪造成功。
7. 仅修改本任务需要的代码、测试和文档，保留工作区已有无关改动。
8. 按项目规范完成定向验证，并只提交本次改动；提交信息使用英文 `[bugfix]` 前缀。
