# Jugg 日志迁移到全局目录方案

## 背景

Jugg 主日志当前保存在工程的 `build/jugg/log`。Gradle `clean` 或人工删除 `build` 后，编译、部署和运行时问题的现场日志会一并丢失。

本方案对应 GitHub Issue #53：将日志迁移到 Jugg 全局目录，同时保留旧路径作为 best-effort 兼容入口。

## 用户可见行为

- 主日志保存到 `~/.jugg/log/<工程名>_<工程绝对路径 SHA-256 前 8 位>/`。
- `~/.jugg` 不可写时，沿用 `JuggGlobalPathManager` 的临时目录回退。
- 同名但绝对路径不同的工程使用不同日志目录。
- `build/jugg/log` best-effort 创建为指向真实日志目录的符号链接；链接创建失败不影响日志写入和 Jugg Run。
- Gradle `clean` 删除兼容链接后，下一次 Jugg Run 自动尝试恢复。
- 已存在的旧 `build/jugg/log` 真实目录先迁移到全局日志目录，再替换为兼容链接。
- 迁移不覆盖全局目录中的同名文件；冲突文件增加 `.legacy-N` 后缀。
- `compile_latest*.log` 属于派生快捷入口，不迁移，由当前日志生命周期重新生成。
- 迁移或链接创建失败时，撤销本轮迁入文件并恢复旧真实目录。
- 日志文件名、轮转数量、大小限制和 `compile_latest.log` 语义保持不变。
- MCP 编译相关工具返回真实 `compile_latest.log` 绝对路径，不依赖兼容链接。

## 实现范围

### 核心路径

- `main/.../project/JuggPathManager.kt`
  - 将 `logDir` 改为全局工程隔离目录。
  - 使用工程根目录名和规范绝对路径的 SHA-256 前 8 位生成目录名。
  - 暴露真实日志目录和旧日志目录，不承担链接生命周期。

### 日志生命周期

- `main/.../logger/JuggLogger.kt`、`FileLogger.kt`
  - 保持日志注册接口不变，通过独立接口登记旧目录并由 `FileLogger` best-effort 创建兼容链接。
  - 复用已有 `recreateIfDeleted()` 生命周期，在日志恢复时一并恢复被 `clean` 删除的链接。
  - 旧真实目录先复制到全局目录，再使用同级临时备份切换链接；失败时按本轮复制记录回滚，不影响旧目录。
  - 已存在的普通文件不覆盖；错误或失效链接重建失败时恢复原链接。

### IDE 生命周期

- `idea/.../loader/JuggManagerCreator.kt`
  - 注册真实日志目录后，单独登记旧目录；运行链路无需感知兼容链接。

### CLI

- 两个 CLI 编译入口在日志注册后单独登记旧目录；基础构建清理 `build/jugg` 后重新创建链接。

### MCP

- `main/.../logger/JuggLogger.kt`
  - 提供当前工程已注册的真实日志目录，保持 MCP 与实际 FileLogger 一致。
- `main/.../ai/mcp/actions/CompileJobManager.kt`
  - 使用已注册的真实目录返回绝对路径；仅未注册日志的测试或非 IDE runtime 按工程路径计算。
- `RestartAppMcpToolAction.kt`
  - 错误提示使用真实日志路径。

### 文档

- 更新 `docs/ai_knowledge` 中日志路径、路径职责和排查入口。
- 更新受影响的中英文 Wiki，中文为内容基准并保持英文镜像。
- 历史文章和历史任务方案不追溯修改。

## 验证策略

路径选择和工程隔离属于独立、稳定、可能被真实破坏的可观察行为，通过测试价值门禁：

- L1 `JuggPathManagerTest`：验证全局目录结构和同名工程隔离。
- L1 `FileLoggerTest`：验证链接创建与恢复、旧真实目录迁移、同名文件保护和失败回滚。
- MCP action 定向测试：验证返回真实绝对日志路径。
- 执行 `:main:test` 的定向测试、`:idea:compileKotlin`、Wiki 校验与 production build。

## 不在范围内

- 不迁移 `build/jugg` 下的数据库、classpath、配置、MCP artifact 或临时文件。
- 不自动移动或删除旧日志目录。
- 不引入日志目录配置项、迁移器、Windows junction 命令或日志复制降级。
- 不修改日志格式、级别、轮转策略和问题报告内容。
