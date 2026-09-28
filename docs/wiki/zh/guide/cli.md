---
title: CLI 指南：命令行增量编译与部署
description: Jugg CLI 命令行工具指南：终端秒级增量构建、日常部署热更实战、参数映射与 AI Agent 自动化集成。
status: active
tags:
  - guide
  - cli
  - build-tools
---

# CLI

Jugg CLI 提供了在终端（Terminal）、CI 脚本或 AI Coding Agent 中直接调用 Jugg 增量构建与热更能力的命令行接口。它通过 Android Studio 中运行的 Jugg 守护服务完成通信，让你无需切换到 IDE 界面即可秒级触发增量编译与设备部署。

## 1. 安装 Jugg CLI 与 Agent Skills

CLI 推荐直接通过 Android Studio 界面进行安装与环境配置：

1. 在 Android Studio 中双击 `Shift` 打开 **Search Everywhere**。
2. 输入并搜索 `Install Jugg Skills`。
3. 在弹出的配置窗口中勾选需要的项：
   - **Install CLI to `$PATH`**：将 `jugg` 可执行脚本链接到系统 PATH（如 `~/.jugg/bin`）。
   - **当前已安装的 Agent**：自动将 `jugg-android-dev-loop` skill 注入到 Claude Code / Antigravity 等 AI 编程助手。
   - **Install agent hooks**：在 Agent 修改源码时自动提示调用增量验证。
4. 点击 **Install** 完成环境注入。

完成后打开任意终端，执行 `jugg version` 即可验证安装成功。

## 2. 输出模式：终端交互与脚本解析 (--console)

CLI 支持三种输出格式，满足人工开发与机器解析的不同诉求：

```bash
# 人工终端使用（默认展示彩色 spinner 动画与动态进度）
jugg --console=rich status

# Agent 或纯文本日志使用（稳定无终端转义字符，不污染上下文）
jugg --console=plain compile

# 自动化脚本消费（stdout 输出结构化 JSON 数据）
jugg --console=json status
```

| 模式 | 适用场景 | 输出特征 |
|---|---|---|
| `rich` | 人工终端日常开发 | 包含加载动画、高亮色块与交互提示 |
| `plain` | AI Agent 代码辅助 | 单行文本追加，去除 spinner 逃逸字符，日志友好 |
| `json` | 自动化脚本与 CI 集成 | stdout 仅保留结构化 JSON，便于 `jq` 或脚本解析 |

> [!TIP]
> 当编写 Shell 脚本或配置自动化流水线时，务必使用 `--console=json` 获取包含 `isCompileSuccess` 与 `isDeploySuccess` 的结构化终态数据。

## 3. 高频实战场景速查 (Quick Recipes)

以下是日常开发中最常用的四种核心场景与对应命令：

### 场景 A：日常代码修改后秒级部署热更 (jugg deploy)
修改 Java、Kotlin 业务代码或 XML 布局文件后，直接在终端执行部署：

```bash
# 触发增量编译并将改动热更至设备（通常 1~3 秒生效）
jugg deploy

# 修改了 Activity 声明或类签名时，强制部署后自动重启目标 App
jugg deploy --always-restart-app true
```

### 场景 B：快速语法与增量编译检查 (jugg compile)
仅验证代码是否能编译通过，不执行 APK 打包和设备推送：

```bash
jugg compile
```

此命令会在 1 秒内返回编译结果与报错行号，是编写代码时的极佳语法自检方式。

### 场景 C：工程状态自检与待编译文件分析 (jugg status)
排查当前工程的增量就绪状态、未编译文件与当前连接设备：

```bash
jugg status
```

### 场景 D：遇到异常或依赖变动时安全重装
当修改了 `build.gradle` 依赖或希望清除旧数据重新验证时：

```bash
# 触发完整 Gradle 构建并重新安装启动
jugg gradle-build

# 清除 App 内部数据并重装 APK
jugg clean-reinstall
```

## 4. 多工程与跨目录调用 (--project-dir)

在目标 Android 工程根目录或任意子目录下执行时，CLI 会自动根据当前工作目录匹配已打开的工程。

若需要在其他路径下跨目录调用，可显式指定项目绝对路径：

```bash
jugg --project-dir /path/to/android/project deploy
```

> [!NOTE]
> 参数名支持 kebab-case 与 camelCase（如 `--project-dir` 与 `--projectDir` 等价）。

## 5. 并发任务控制策略 (--if-compiling)

当上一次触发的编译仍在运行，再次执行命令时可通过全局参数控制行为：

```bash
# 默认行为：等待前一个任务完成后再开始执行
jugg --if-compiling wait deploy

# 中断策略：立即中断上一轮后台编译，开启最新任务
jugg --if-compiling interrupt deploy
```

## 6. AI Agent 自动化集成建议

若你在日常开发中搭配 AI 编码助手（如 Claude Code、Cursor、Antigravity 等）：

1. **优先使用 compile 验证**：指导 Agent 在修改代码后先执行 `jugg --console=plain compile`，验证无语法与类型错误。
2. **谨慎让 Agent 自动 deploy**：只有当明确要求进行设备端运行或 UI 验证时，再让 Agent 调用 `jugg deploy`。
3. **同时判断编译与部署结果**：在解析返回时，必须同时检查 `isCompileSuccess` 与 `isDeploySuccess`，避免部署因设备脱机而失败被误判为成功。

## 7. 常见问题排查与解决

| 异常现象 | 核心根因 | 解决步骤 |
|---|---|---|
| **`CLI 找不到项目`** | Android Studio 未打开对应工程，或未初始化 Jugg | 确认 IDE 中已打开目标项目并完成首次 Sync，或传 `--project-dir` |
| **`连接端口失败`** | IDE 插件守护端口未就绪（默认范围 `12320-12329`） | 确认 Android Studio 仍在运行，且 Jugg 插件已正常启动 |
| **命令一直处于 Waiting 状态** | 存在卡住的后台构建任务 | 执行 `jugg status` 观察状态，必要时传 `--if-compiling interrupt` |
| **Windows 提示 Python 找不到** | 系统 PATH 中缺少 Python 3.7+ 解释器 | 检查 `python3 --version`，确保已安装 Python 并加入系统环境变量 |

---

## 下一步指引

- 📖 **[Jugg CLI 完整命令手册](../reference/cli-commands.md)**：查阅全部 16 个子命令与高级参数
- 🚀 **[日常热更运行指南](../guide/run.md)**：深入了解热重载与兼容部署模式
- 🤖 **[Agent Skills 集成详解](../capabilities/tools/agent-skills.md)**：探索 Jugg 在自动化智能体中的最佳实践
