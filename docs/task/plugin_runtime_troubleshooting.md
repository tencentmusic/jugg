# Jugg 插件运行时问题排查指南

## 1. 目录结构速查

Jugg 运行时产物统一存放在各 Android 项目的 `build/jugg/` 目录下（由 `JuggPathManager` 定义）。

```
{projectDir}/
└── build/jugg/                        # juggRootDir：所有运行时产物的根目录
    ├── log/                           # logDir：插件日志
    │   ├── compile_latest.log         # 软链接 → 最新一次日志文件
    │   ├── compile_latest-1.log       # 软链接 → 上一次日志文件
    │   └── compile_YYYY-MM-DD_HH-mm-ss.0.log   # 实际日志文件
    ├── build/                         # compileRootDir：编译中间产物
    │   └── staging/                   # stagingDir：本次增量编译输出的 dex/资源
    ├── database/                      # databaseDir：持久化状态
    │   ├── apk/                       # APK 解析后的 SQLite 数据库（*.db）
    │   ├── project_infos.db/          # projectInfosDir：项目结构信息
    │   │   ├── project_infos.json     # IDE 侧项目信息
    │   │   ├── gradle_project_infos.json  # Gradle 侧项目信息
    │   │   └── base_build_cmd.txt     # 上次全量编译命令
    │   ├── compile_context.db/        # 编译上下文（classpath、模块信息）
    │   └── deploy_history.db/         # 部署历史（用于增量恢复）
    ├── classpath/                     # localClasspathStoragePathManager.rootDir
    │   ├── root/                      # classpathDir：classpath jar 文件
    │   ├── apk/                       # apkDir：APK 文件缓存
    │   └── libraries/                 # librariesBackupDir：依赖库备份
    ├── config/                        # configDir：运行时配置
    │   ├── readProjectInfo.gradle.kts # Gradle 信息读取脚本
    │   ├── jugg-runtime.jar           # 设备端运行时 jar
    │   └── custom_compilers/          # 自定义编译器插件
    ├── tmp/                           # tmpDir：临时文件
    │   └── diff/                      # remoteDiffDir：远程编译 diff 结果
    └── mcp_fetch/                     # MCP 工具拉取缓存

~/.jugg/                               # globalJuggRootDir：跨项目共享数据
└── const_ref/                         # constRefDir：常量引用分析缓存
    ├── const_ref_shared.db            # 常量引用数据库
    └── repo_fingerprint.db            # 仓库指纹数据库
```

---

## 2. 排查前：保存现场

**在任何操作之前先备份关键文件**，避免复现操作覆盖原始证据。

```bash
PROJECT_DIR="/path/to/your/android/project"
BACKUP_DIR="~/Desktop/jugg_debug_$(date +%Y%m%d_%H%M%S)"
mkdir -p $BACKUP_DIR

# 日志
cp -r $PROJECT_DIR/build/jugg/log/ $BACKUP_DIR/log/

# 数据库状态（用于复现 DB 相关问题）
cp -r $PROJECT_DIR/build/jugg/database/ $BACKUP_DIR/database/

# 项目信息快照
cp -r $PROJECT_DIR/build/jugg/database/project_infos.db/ $BACKUP_DIR/project_infos/
```

---

## 3. 日志分析

### 3.1 找到日志文件

```bash
# 最新日志（软链接）
open $PROJECT_DIR/build/jugg/log/compile_latest.log

# 或列出所有日志（按时间倒序）
ls -lt $PROJECT_DIR/build/jugg/log/*.log
```

### 3.2 日志格式

```
[2026-03-16 16:13:27.109] [FINE   ] [ClassName] message
```

- 时间戳精确到**毫秒**
- 级别：`FINE`（debug）、`INFO`、`WARNING`、`SEVERE`
- 方括号内为日志来源类名（由 `logger.getInstance("ClassName")` 指定）

### 3.3 关键日志标志

| 事件 | 搜索关键词 |
|------|-----------|
| 编译开始 | `Jugg compile started` |
| 增量/全量编译判断 | `preprocessIncrementalCompile` |
| 无文件变化弹框 | `confirmFallbackWhenNoFileChanges` |
| EDT 异步派发（文件变化） | `dispatching to background` |
| TaskRunnerManager 锁等待 | `waiting for TaskRunnerManager lock` / `waitCost=` |
| APK 数据库初始化 | `initAfterInstall parsed apk start` / `database all init finish` |
| 部署开始 | `deploy start` |
| 编译/部署耗时 | `cost` / `cost ${costTime}ms` |

### 3.4 时序分析技巧

定位某段操作的耗时，取前后两行时间戳相减：

```bash
grep -n "关键词A\|关键词B" compile_latest.log
```

---

## 4. 常见问题排查

### 4.1 IDE 卡顿 / 无响应

**症状**：点击按钮或操作 IDE 时短暂冻结（100ms ~ 数秒）。

**根因方向**：EDT（Event Dispatch Thread）被阻塞。常见原因：
- EDT 调用了持有锁的同步方法（如 `@Synchronized` 方法在编译线程持锁时）
- EDT 调用了 `invokeAndWait`（嵌套死锁）
- EDT 调用了耗时 I/O（SQLite 查询、文件读写）

**排查步骤**：

1. 确认卡顿时间点（毫秒精度日志）
2. 搜索该时间段内的 `waiting for ... lock` / `waitCost=` 日志
3. 检查 `dispatching to background` 是否出现（若未出现说明 EDT 调用了同步方法）
4. 对比 `@Synchronized` 方法列表与 EDT 可能的调用路径

### 4.2 增量编译异常回退

**症状**：没有修改文件，每次仍触发全量 Gradle 编译。

**排查**：

```bash
grep "No file changes\|isNoFileChanges\|getChangedFilesSinceLastFullCompiled" compile_latest.log
```

检查 `deploy_history.db/` 是否损坏，必要时清空：

```bash
rm -rf $PROJECT_DIR/build/jugg/database/deploy_history.db/
```

### 4.3 APK 数据库初始化失败

**症状**：日志出现 `database all init finish` 耗时异常（>5s），或部署时报 `databaseNotFound`。

**排查**：

```bash
grep "initAfterInstall\|database all init\|databaseNotFound" compile_latest.log
```

检查 APK 数据库文件：

```bash
ls -lh $PROJECT_DIR/build/jugg/database/apk/
```

---

## 5. TDD 流程：复现 → 修复 → 验证

发现问题后，**严格按照以下顺序**处理，避免在未能稳定复现前就开始修改代码。

### Step 1：稳定复现

1. **备份现场**（见第 2 节）
2. 根据日志确定**最小复现步骤**（哪个操作、哪个时间点）
3. 编写**复现测试**，使测试在当前代码上 **FAIL**：

```kotlin
@Test
fun `EDT calling addChangedFile should not block`() {
    // 构造 EDT 场景，断言调用后立即返回（不阻塞）
    val latch = CountDownLatch(1)
    SwingUtilities.invokeAndWait {
        val start = System.currentTimeMillis()
        deployFileManager.addChangedFile(listOf(fakeChangedFile))
        val elapsed = System.currentTimeMillis() - start
        latch.countDown()
        assertTrue("addChangedFile on EDT took ${elapsed}ms, expected <10ms", elapsed < 10)
    }
    latch.await(5, TimeUnit.SECONDS)
}
```

4. 运行测试，**确认 FAIL**，记录失败信息

### Step 2：实现修复

在测试 FAIL 已确认后，再动手修改代码。

修改原则：
- 优先**最小化改动**，不引入额外复杂度
- 修改点必须有对应的日志（便于线上验证）
- 若涉及并发，明确写清锁的使用范围和线程假设

### Step 3：验证

**自动化验证**：

```bash
./gradlew :main:test --tests "*YourTestClass*"
```

确认之前 FAIL 的测试现在 **PASS**，且回归测试全量通过：

```bash
./gradlew :main:test :idea:test
```

**手动验证**：

1. 打包插件：`./gradlew :idea:buildPlugin`
2. 在 Android Studio 中安装新插件
3. 按照复现步骤操作，确认问题消失
4. 观察日志中新增的诊断日志是否符合预期

```bash
# 验证 EDT 派发路径是否被触发
grep "dispatching to background" $PROJECT_DIR/build/jugg/log/compile_latest.log
```

---

## 6. 需要提供给他人的环境信息

提交 Bug 或请他人协助排查时，需附带以下文件：

| 文件 | 路径 | 说明 |
|------|------|------|
| 运行日志 | `build/jugg/log/compile_latest.log` | 必须，问题发生时的完整日志 |
| 项目信息 | `build/jugg/database/project_infos.db/project_infos.json` | 模块、APK 配置快照 |
| Gradle 项目信息 | `build/jugg/database/project_infos.db/gradle_project_infos.json` | 依赖结构 |
| APK 数据库 | `build/jugg/database/apk/*.db` | 用于复现 DB 状态相关问题 |
| 编译历史 | `build/jugg/database/deploy_history.db/` | 用于复现增量状态相关问题 |
| 插件版本 | Android Studio → Help → About | 记录 IDE 版本和 Jugg 插件版本号 |
