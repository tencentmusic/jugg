# Standalone Runtime 多项目复用方案

## 背景

当前 standalone CLI 按项目启动独立 Runtime 进程。多个项目并行使用时会重复加载相同运行环境，也使进程管理和停止语义更复杂。

## 已确认目标

- 一个 standalone Runtime 进程可同时承载多个项目。
- Runtime 收到未知项目的首个合法项目级请求时，自动注册并初始化该项目。
- 不考虑新旧 CLI 之间的兼容。
- `jugg stop` 停止当前 Jugg 根目录下的全部 standalone Runtime，从而同时停止其中所有项目。

## 行为设计

### Runtime 自动注册

- `version`、`list-projects` 等全局请求不触发项目注册。
- 项目级请求先完成工具名、参数结构和 `projectDir` 校验。
- 请求合法且项目尚未注册时，Runtime 初始化该项目并继续执行原请求。
- 非法工具、非法参数或非法路径不产生注册副作用。
- 单个项目初始化失败只返回该请求的错误，不影响已注册项目。
- 同一项目的并发初始化共享结果，不同项目的初始化互不阻塞。
- 初始化中途失败时关闭该项目已创建的资源，并允许后续请求重试。

### CLI Runtime 选择

- 优先选择已经注册目标项目的 Runtime。
- 未找到项目 owner 且未强制 IDEA Runtime 时，复用任意可用 standalone Runtime。
- 当前没有 standalone Runtime 时才启动新进程。
- standalone 启动使用用户级全局锁，避免不同项目并发启动多个进程。
- 复用尚未注册目标项目的 Runtime 时，目标项目在首个请求完成前作为 pending project 传递；慢初始化期间继续输出启动心跳。
- 缺少 `complete_flag` 的 hook 不触发新项目自动注册。

### 停止语义

- CLI 调用 launcher 的 `--stop-all`。
- Bootstrap 停止相同 `jugg.root.dir` 下的全部 standalone Runtime。
- 其他 Jugg 根目录和无关 Java 进程不受影响。

## 非目标

- 不提供显式 `register-project` MCP 工具。
- 不提供单项目注销、休眠、LRU 或独立 idle 生命周期。
- 不保留旧版 `--stop-project` 行为或 CLI 兼容分支。

## 预计修改

- `StandaloneProjectRegistry`：合法项目请求触发自动初始化。
- standalone CLI：复用已有进程、全局启动锁、pending project 和初始化心跳。
- `jugg stop` 与 `StandaloneBootstrap`：改为 stop-all。
- 同步 standalone 架构、CLI 和 MCP 知识文档。

## 验证计划

- Runtime：未知项目的合法请求可自动注册；非法请求不注册。
- Runtime 隔离：一个项目等待项目锁时，其他项目仍可响应；初始化中断后资源被清理且可重试。
- CLI：复用未注册目标项目的 standalone Runtime；不同项目共享启动锁；hook 不误注册；pending project 可解析；慢初始化有心跳。
- Stop：同一 Jugg 根目录下的 standalone 进程全部停止，外部进程保持运行。
- 执行定向 JVM/Python 测试、Kotlin 编译和 Python 兼容检查。
