# Gradle `clean` 场景下 Jugg init script 兼容方案

## 1. 背景

当前 Jugg 在 Gradle 回退编译前，会将两个编译期必须持续存在的文件写入工程内：

- `.gradle/jugg/readProjectInfo.gradle.kts`
- `.gradle/jugg/jugg-runtime.jar`

然后通过编译命令自动注入：

- `-I <init-script-path>`
- `-Pjugg.inject.application.enable=...`

之所以放在工程目录内，是因为远程编译时这些文件必须跟随工程一起同步到远端；而之所以不能继续放在 `build/`，是因为用户一旦执行 `clean assemble`，`build/` 下的同名文件会被删掉。

## 2. 最终采用方案

**只把 Gradle 必需的 init script 与 runtime jar 固定到项目根下的 `.gradle/jugg`，其他运行时配置仍保留在 `build/jugg/config`。**

目录职责拆分如下：

- **稳定目录（不应被 `clean` 删除）**
  - `.gradle/jugg/`
    - `readProjectInfo.gradle.kts`
    - `jugg-runtime.jar`

- **常规运行时配置目录（继续放在 `build/jugg/config`）**
  - `build/jugg/config/`
    - `custom_compilers/`
    - `agent_setup.md`
    - `jugg-android-dev-loop/`
    - `custom_config.json`

- **临时/构建态目录（继续放在 `build/jugg`）**
  - `build/jugg/tmp/`
  - `build/jugg/classpath/`
  - `build/jugg/database/`
  - `build/jugg/log/`
  - `build/jugg/mcp_fetch/`

## 3. 为什么这样拆

### 3.1 优点

- **兼容 `clean`**：`clean` 不会删除项目根 `.gradle/jugg`
- **仍在工程内**：远程编译可以继续同步
- **不扩大迁移范围**：只有真正受 `clean` 影响的 Gradle 资产被迁移
- **保留原有配置习惯**：`custom_compilers`、安装说明等仍在 `build/jugg/config`
- **兼容单次命令语义**：不需要把 `clean assemble` 强拆成两次 Gradle invocation

## 4. 实际改造点

### 4.1 `JuggPathManager`

- 保留 `configDir = File(projectDir, "build/jugg/config")`
- 新增 `stableGradleDir = File(projectDir, ".gradle/jugg")`
- `initGradleFilePath` 指向 `.gradle/jugg/readProjectInfo.gradle.kts`
- `runtimeJarFilePath` 指向 `.gradle/jugg/jugg-runtime.jar`

### 4.2 `GradleScriptWriter`

继续在编译前写文件，但目标改为：

- `.gradle/jugg/readProjectInfo.gradle.kts`
- `.gradle/jugg/jugg-runtime.jar`

### 4.3 `GradleApplicationInjector`

运行时依赖不再扫描 `configDir` 下全部 jar，而是显式依赖：

- `.gradle/jugg/jugg-runtime.jar`

### 4.4 `JuggGradleCompileOptions`

`remoteInitGradleFilePath` 仍然基于 `initGradleFilePath.relativeTo(projectRootPath)` 计算，因此迁移后会自然变成：

- `<remoteProjectPath>/.gradle/jugg/readProjectInfo.gradle.kts`

### 4.5 远程同步规则

rsync include 规则同时保留两类目录：

- `/.gradle/jugg/**`：保证 init script 与 runtime jar 会同步到远端
- `/build/jugg/config/**`：保持其他运行时配置继续可用

## 5. 受影响的辅助逻辑

以下路径继续跟随 `configDir`，因此保持在 `build/jugg/config`：

- `ClientSetupDocExporter`
- `custom_compilers` 运行时配置说明
- `CustomConfigManager`

## 6. 测试验证点

已补的关键验证点：

1. `JuggPathManager.configDir` 保持 `build/jugg/config`，而 `initGradleFilePath` / `runtimeJarFilePath` 指向 `.gradle/jugg`
2. `JuggGradleCompileOptions.remoteInitGradleFilePath` 保持工程内 `.gradle/jugg` 相对路径
3. `SyncFileCommand.getRsyncArguments()` 在远程同步时同时保留 `.gradle/jugg` 与 `build/jugg/config`
4. `ClientSetupDocExporter` 继续将说明与 skill 解压到 `build/jugg/config`

## 7. 不采用的方案

### 7.1 把 `clean assemble` 拆成两次 Gradle 调用

问题：

- 需要准确解析用户命令
- 改变单次命令语义
- 对复杂命令容易漏边界
- 本地与远端都要维护一套拆分逻辑

### 7.2 把整个 `config` 目录迁到 `.gradle/jugg`

问题：

- 迁移范围大于实际需要
- 会改变现有 `build/jugg/config` 相关说明与使用习惯
- 容易把与 `clean` 无关的运行时资产一并搬走

## 8. 结论

**最终采用“只迁移 `readProjectInfo.gradle.kts` 与 `jugg-runtime.jar`”的 `.gradle/jugg` 方案。**

它同时满足：

- 不被 `clean` 删除
- 仍位于工程目录内，远程编译可同步
- 不要求用户额外维护新的顶层目录
- 改动范围最小，且容易测试与回归
