# View Hierarchy Server 方案（替代 uiautomator dump）

> 文档版本: v1.0
> 创建时间: 2026-03-02
> 适用范围: `jvmti_agent/`（设备端）+ `main/.../mcp/actions/`（IDE 端）
> 前置依赖: `CODELOCATOR_SOURCE_ANALYSIS_PLAN.md` 的分析结论
> 一致性规则: 文档与代码冲突时，以代码为准。

---

## 1. 背景与目标

### 1.1 现状问题

当前 `layout-dump` 和 `tap`（元素模式）均依赖 `adb shell uiautomator dump`：

- **慢**：每次调用需 3-8 秒（启动独立 uiautomator 进程）
- **不稳定**：部分设备超时或返回空 XML；动画/过渡期容易失败
- **信息有限**：仅 Accessibility 节点，无法获取 View 自定义属性

涉及文件：
- `LayoutDumpMcpToolAction.kt:86-89`（`uiautomator dump` 调用）
- `TapMcpToolAction.kt:306-309`（`dumpUiHierarchy` 方法）

### 1.2 目标

1. 在目标 App 进程内运行 View Hierarchy Server，直接遍历 View 树
2. 通过 LocalSocket 与 IDE 通信，提供 layout dump + 元素查找 + 点击能力
3. 由 BootstrapApplication 负责注册，单路径加载并保持进程内单例
4. 将 `TapMcpToolAction` 的元素查找 + 点击逻辑移至设备端，实现原子操作
5. 移除 uiautomator 命令行兜底，Server 不可用时直接返回错误

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────┐
│  IDE 端 (IntelliJ Plugin)                       │
│                                                 │
│  LayoutDumpMcpToolAction ──┐                    │
│  TapMcpToolAction ─────────┤                    │
│                            ▼                    │
│                  ViewHierarchyClient            │
│                    │  adb forward               │
│                    │  localabstract:jugg_vh      │
└────────────────────┼────────────────────────────┘
                     │ LocalSocket
┌────────────────────┼────────────────────────────┐
│  设备端 (App 进程)  ▼                            │
│                                                 │
│             ViewHierarchyServer                 │
│               (LocalServerSocket)               │
│                    │                            │
│         ┌─────────┼─────────┐                   │
│         ▼         ▼         ▼                   │
│    layout_dump  find_and_tap  find_elements     │
│         │         │                             │
│         ▼         ▼                             │
│    ViewTreeDumper (遍历 View 树)                 │
│         │                                       │
│    ┌────┴─────────────┐                         │
│    ▼                  ▼                         │
│  View 节点         Compose 节点                  │
│  (DecorView 递归)  (SemanticsNode，待定)         │
│                                                 │
│  注册方式:                                       │
│    BootstrapApplication.onCreate()               │
│    (进程内单例保护，只加载一次)                       │
└─────────────────────────────────────────────────┘
```

---

## 3. 设备端设计

### 3.1 单路径注册机制

```java
/**
 * 单例入口，确保 Server 只启动一次。
 * BootstrapApplication 在 onCreate() 调用此方法。
 */
public class ViewHierarchyServerLoader {
    private static volatile boolean sInitialized = false;

    public static synchronized void init(Context context) {
        if (sInitialized || context == null) return;
        if (ViewHierarchyServer.start(context)) {
            sInitialized = true;
        }
    }
}
```

**路径：BootstrapApplication**
- 在 `BootstrapApplication.onCreate()` 末尾调用 `ViewHierarchyServerLoader.init(this)`
- 覆盖场景：Jugg 增量部署模式下的正常启动

> 更新（2026-03-03）：已移除“JVMTI Agent attach 触发 ViewHierarchyServer 初始化”路径，现网代码仅保留 BootstrapApplication 初始化入口。

### 3.2 ViewHierarchyServer（LocalSocket 服务）

```
模块位置：jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/

核心类：
  ViewHierarchyServer.java       - LocalServerSocket 监听，请求分发
  ViewHierarchyServerLoader.java - 单例初始化入口
  ViewTreeDumper.java            - View 树遍历与序列化
  ElementFinder.java             - 元素查找逻辑
  ViewTapper.java                - 点击执行
```

**Socket 命名**：`jugg_vh_{pid}`（加 pid 后缀避免多 App 冲突）

**线程模型**：
- Server 在独立子线程监听连接
- 收到请求后，通过 `Handler.post()` 切到主线程执行 View 树操作
- 主线程操作完成后通过 CountDownLatch 通知 IO 线程返回响应
- 超时保护：主线程操作上限 5 秒

### 3.3 通信协议

请求和响应均为单行 JSON，以换行符 `\n` 作为消息分隔。

#### 请求格式

```json
{
  "action": "layout_dump | find_elements | find_and_tap | tap_coordinate",
  "params": { ... }
}
```

#### Action 定义

**layout_dump**：遍历并返回完整 View 树

```json
// 请求
{"action": "layout_dump", "params": {}}

// 响应（成功）
{
  "status": "ok",
  "data": {
    "windows": [
      {
        "windowType": "activity | dialog | popup | system",
        "title": "MainActivity",
        "root": { /* ViewNode 递归结构 */ }
      }
    ]
  }
}

// 响应（如果数据量超过阈值 100KB，改为文件模式）
{
  "status": "ok",
  "data": {
    "mode": "file",
    "filePath": "/data/local/tmp/jugg_vh/layout_xxxx.json"
  }
}
```

**find_elements**：按条件查找元素

```json
// 请求
{
  "action": "find_elements",
  "params": {
    "text": "登录",
    "resourceId": "com.example:id/btn_login",
    "contentDesc": "Login button",
    "className": "android.widget.Button"
  }
}

// 响应
{
  "status": "ok",
  "data": {
    "matchCount": 1,
    "elements": [
      {
        "text": "登录",
        "resourceId": "com.example:id/btn_login",
        "className": "android.widget.Button",
        "bounds": {"left": 100, "top": 200, "right": 300, "bottom": 260},
        "centerX": 200,
        "centerY": 230,
        "visibility": "visible",
        "clickable": true
      }
    ]
  }
}
```

**find_and_tap**：查找 + 点击原子操作

```json
// 请求
{
  "action": "find_and_tap",
  "params": {
    "text": "登录",
    "resourceId": null,
    "contentDesc": null,
    "className": null
  }
}

// 响应（唯一匹配，已点击）
{
  "status": "ok",
  "data": {
    "tapped": true,
    "matchCount": 1,
    "x": 200,
    "y": 230,
    "matchedElement": "text=\"登录\", class=\"Button\", bounds=[100,200][300,260]"
  }
}

// 响应（多匹配，未点击）
{
  "status": "error",
  "message": "Multiple elements matched (2). Use coordinate mode to tap.",
  "data": {
    "tapped": false,
    "matchCount": 2,
    "elements": [ ... ]
  }
}
```

**tap_coordinate**：指定坐标点击

```json
// 请求
{"action": "tap_coordinate", "params": {"x": 200, "y": 230}}

// 响应
{"status": "ok", "data": {"x": 200, "y": 230}}
```

### 3.4 ViewNode 数据结构

```json
{
  "className": "android.widget.LinearLayout",
  "id": "com.example:id/container",
  "idHex": "0x7f080001",
  "text": "",
  "contentDesc": "",
  "tag": null,
  "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 1920},
  "visibility": "visible",
  "alpha": 1.0,
  "clickable": false,
  "enabled": true,
  "focused": false,
  "selected": false,
  "padding": {"left": 0, "top": 0, "right": 0, "bottom": 0},
  "children": [ /* 递归 ViewNode */ ]
}
```

> 具体属性清单待 `CODELOCATOR_SOURCE_ANALYSIS_PLAN.md` 分析后确认最终版本。
> Compose 节点的字段设计同样依赖分析结论。

### 3.5 View 树遍历核心逻辑（伪代码）

```java
public class ViewTreeDumper {

    /**
     * 获取所有 Window 的 DecorView 列表。
     * 通过反射 WindowManagerGlobal.mViews 获取。
     * 具体反射路径待 CodeLocator 源码分析后确认。
     */
    public List<WindowInfo> getAllWindows() {
        // 反射 WindowManagerGlobal.getInstance().mViews
        // 兼容不同 Android 版本的字段名
        // 参考 CodeLocator 的实现
    }

    /**
     * 递归遍历 View 树，生成 ViewNode。
     */
    public ViewNode dumpView(View view) {
        ViewNode node = extractProperties(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                node.children.add(dumpView(group.getChildAt(i)));
            }
        }
        // Compose 节点处理（待分析后补充）
        return node;
    }
}
```

### 3.6 元素查找与点击逻辑

```java
public class ElementFinder {

    /**
     * 在 View 树中按条件查找匹配元素。
     * 所有条件为 AND 逻辑，与现有 TapMcpToolAction 语义一致。
     */
    public List<MatchedElement> find(
        String text, String resourceId, String contentDesc, String className
    ) {
        List<WindowInfo> windows = dumper.getAllWindows();
        List<MatchedElement> results = new ArrayList<>();
        for (WindowInfo window : windows) {
            findInView(window.rootView, text, resourceId, contentDesc, className, results);
        }
        return results;
    }
}

public class ViewTapper {

    /**
     * 点击指定 View。优先 performClick()，降级 Instrumentation 注入。
     */
    public void tap(View view) {
        if (view.isClickable()) {
            view.performClick();
        } else {
            // 降级：计算 View 中心坐标，通过 Instrumentation 注入 MotionEvent
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            int centerX = location[0] + view.getWidth() / 2;
            int centerY = location[1] + view.getHeight() / 2;
            injectTap(centerX, centerY);
        }
    }
}
```

---

## 4. IDE 端设计

### 4.1 ViewHierarchyClient

```
模块位置：main/src/main/java/com/sickworm/intellij/jugg/mcp/viewhierarchy/

核心类：
  ViewHierarchyClient.kt    - 封装 adb forward + LocalSocket 通信
  ViewHierarchyProtocol.kt  - 请求/响应数据类
```

**连接流程**：

```
1. adb forward localabstract:jugg_vh_{pid} → 检查 Server 是否可用
2. 如果可用 → 建立 socket 连接 → 发送请求 → 等待响应
3. 如果不可用或超时 → 直接返回错误（无 fallback）
```

**adb forward 管理**：

```kotlin
class ViewHierarchyClient(private val adb: IDeviceAdb) {

    /**
     * 通过 App 内 Server 执行操作；失败时直接返回错误。
     */
    fun <T> executeWithServer(action: (socket: Socket) -> T): T {
        return connectToServer()?.use { socket ->
            action(socket)
        } ?: error("ViewHierarchy server unavailable")
    }
}
```

### 4.2 LayoutDumpMcpToolAction 改造

```kotlin
// 改造前：直接调用 uiautomator dump
private fun tryDumpAndPull(adb, remoteDir, remoteFile, localFile): Boolean {
    adb.execAdbShellCmd("uiautomator dump $remoteFile")
    return adb.pull(remoteFile, localFile)
}

// 改造后：仅走 ViewHierarchyClient
private fun layoutDumpAction(runtime: IMcpRuntime): McpToolResult {
    val client = ViewHierarchyClient(adb)
    // 发送 layout_dump 请求
    // 接收响应 JSON 或文件路径
    // Server 失败直接返回 ERROR
}
```

### 4.3 TapMcpToolAction 改造

```kotlin
// 元素模式改造：查找+点击 由 App Server 原子执行
private fun tapByElement(adb, text, resourceId, contentDesc, className): McpToolResult {
    val client = ViewHierarchyClient(adb)
    // 发送 find_and_tap 请求
    // Server 端原子执行：查找 → 唯一匹配则点击 → 返回结果
    // 多匹配返回候选列表（语义与现有行为一致）
    // Server 失败直接返回 ERROR
}

// 坐标模式和百分比模式：保持现有 adb shell input tap 不变
// 这两个模式无需经过 App Server
```

**IDE 端 XML 解析代码（legacy）已删除**：`tap` 元素模式不再走 `uiautomator dump`。

---

## 5. 错误返回策略

```
优先级：App Server (LocalSocket)（唯一链路）

触发 ERROR 的条件：
1. App Server 未启动（BootstrapApplication 初始化未执行）
2. adb forward 失败
3. Socket 连接超时（3 秒）
4. 请求执行超时（8 秒）
5. Server 返回错误

行为：
- layout_dump → 直接返回 ERROR
- tap 元素模式 → 直接返回 ERROR
- tap 坐标/百分比模式 → 不受影响（本来就是 adb shell input tap）
```

---

## 6. 文件变更清单

### 6.1 新增文件（设备端）

| 文件 | 说明 |
|------|------|
| `jvmti_agent/src/main/java/.../viewhierarchy/ViewHierarchyServer.java` | LocalSocket 服务主类 |
| `jvmti_agent/src/main/java/.../viewhierarchy/ViewHierarchyServerLoader.java` | 单例初始化入口 |
| `jvmti_agent/src/main/java/.../viewhierarchy/ViewTreeDumper.java` | View 树遍历与序列化 |
| `jvmti_agent/src/main/java/.../viewhierarchy/ElementFinder.java` | 元素查找 |
| `jvmti_agent/src/main/java/.../viewhierarchy/ViewTapper.java` | 点击执行 |
| `jvmti_agent/src/main/java/.../viewhierarchy/ViewNode.java` | 节点数据结构 |
| `jvmti_agent/src/main/java/.../viewhierarchy/WindowInfo.java` | 窗口信息 |

### 6.2 新增文件（IDE 端）

| 文件 | 说明 |
|------|------|
| `main/src/main/java/.../mcp/viewhierarchy/ViewHierarchyClient.kt` | Socket 通信客户端 |
| `main/src/main/java/.../mcp/viewhierarchy/ViewHierarchyProtocol.kt` | 协议数据类 |

### 6.3 修改文件

| 文件 | 变更 |
|------|------|
| `jvmti_agent/.../BootstrapApplication.java` | `onCreate()` 末尾增加 `ViewHierarchyServerLoader.init(this)` |
| `main/.../mcp/actions/LayoutDumpMcpToolAction.kt` | 改为仅走 `ViewHierarchyClient`（Server-only） |
| `main/.../mcp/actions/TapMcpToolAction.kt` | 元素模式改为仅走 `find_and_tap`（Server-only） |

---

## 7. 实施阶段

### Phase 1: CodeLocator 源码分析

- 执行 `CODELOCATOR_SOURCE_ANALYSIS_PLAN.md`
- 产出分析报告，确认 View 树遍历的反射路径、属性清单、Compose 处理方式
- 根据分析结论修订本文档的 3.4（ViewNode 结构）和 3.5（遍历逻辑）

### Phase 2: 设备端 Server 实现

- 实现 `ViewHierarchyServer` + `ViewTreeDumper`（layout_dump 能力）
- 实现单路径注册机制（BootstrapApplication）
- 单独验证：手动 `adb forward` + 脚本发送 JSON 请求，确认 dump 结果正确

### Phase 3: IDE 端 Client + LayoutDump 改造

- 实现 `ViewHierarchyClient`
- 改造 `LayoutDumpMcpToolAction`，增加 Server-only 逻辑
- 验证 `layout-dump` MCP 工具端到端正常

### Phase 4: 元素查找 + 点击（Tap 改造）

- 设备端实现 `ElementFinder` + `ViewTapper`
- 设备端增加 `find_elements`、`find_and_tap`、`tap_coordinate` action
- 改造 `TapMcpToolAction` 元素模式
- 验证 `tap` MCP 工具三种模式端到端正常

### Phase 5: Compose 支持（可选，依 Phase 1 分析结论决定）

- 如 CodeLocator 有 Compose 支持方案：参考实现
- 如 CodeLocator 无 Compose 支持：独立调研 SemanticsNode 遍历方案，单独排期

---

## 8. 验证方案

### 8.1 功能验证

| 场景 | 预期 |
|------|------|
| layout_dump（Server 可用） | 返回 JSON 格式 View 树，包含多窗口 |
| layout_dump（Server 不可用） | 直接返回错误（无 fallback） |
| tap 元素模式（唯一匹配） | Server 端原子查找+点击，返回成功 |
| tap 元素模式（多匹配） | 返回候选列表，不执行点击 |
| tap 元素模式（无匹配） | 返回错误，附带可点击元素提示 |
| tap 元素模式（Server 不可用） | 直接返回错误（无 fallback） |
| tap 坐标/百分比模式 | 不经过 Server，行为与改造前一致 |

### 8.2 性能验证

| 指标 | uiautomator 基线 | Server 目标 |
|------|-------------------|-------------|
| layout_dump 耗时 | 3-8 秒 | < 500ms |
| tap 元素模式耗时 | 4-10 秒（dump + 解析 + tap） | < 800ms |
| 首次连接耗时（含 adb forward） | N/A | < 1 秒 |

### 8.3 稳定性验证

- 连续 20 次 layout_dump，无失败
- App 前后台切换后 Server 仍可用
- App 进程重启后 Server 自动重新初始化（通过 BootstrapApplication）

---

## 9. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 反射获取 DecorView 列表在高版本 Android 被限制 | 参考 CodeLocator 的兼容方案；必要时走 `View.getRootView()` 降级 |
| LocalSocket 权限问题 | 使用 abstract namespace 避免文件权限问题；socket 命名加 pid 防冲突 |
| 主线程 dump 耗时导致 ANR | 设置 5 秒超时；View 树过深时设置深度限制（如 50 层） |
| JVMTI Agent 路径获取 Context 困难 | 通过反射 `ActivityThread.currentApplication()` 获取 |
| Compose 节点支持复杂度高 | 作为独立 Phase，不阻塞核心功能上线 |

---

## 10. 关联文档

- 前置分析：`CODELOCATOR_SOURCE_ANALYSIS_PLAN.md`
- 现有 MCP 工具文档：`docs/ai_knowledge/08_mcp_usage.md`
- 现有 MCP 设计文档：`docs/ai_knowledge/08_mcp_design.md`
- 代码入口速查：`docs/ai_knowledge/98_code_map.md`
