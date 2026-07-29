# Classpath Resource Overlay Hook 实现方案

## 背景与失败证据

Kotlin 1.9 / Compose 1.6 的 legacy Compose resource 通过 `ClassLoader#getResource("values/strings.xml")` 读取 APK 根目录 Java resource。Jugg 当前能把变更编译为 `values/strings.xml` overlay，但现有部署只对 Android `res/`、`assets/` 等路径天然生效，ClassLoader 不会读取 `.overlay`。

现场已确认：

- 未 hook 时，`.overlay/base.apk/values/strings.xml` 已是新内容，应用仍打印旧值 `Android baseline title`。
- 重转换 `java/lang/ClassLoader` 并覆盖返回值后，应用打印 `Android baseline title hook ok`。
- 现有部署日志中 `changedOverlays: [Asset:values/strings.xml]`，但 `isNeedRestartApp:false`，无法可靠清除 Compose resource 进程内缓存。

## 目标

1. `ClassLoader#getResource` 对宿主应用 ClassLoader 优先查找 `.overlay/base.apk`。
2. 同时支持独立 overlay 文件和 `resource.ap_` ZIP 条目。
3. 找不到 overlay 时无条件回落到原始 ClassLoader 行为。
4. 部署 APK 根目录文件成功后重启应用进程，清除 Compose 和 `JarURLConnection` 缓存。
5. 保持 `CompileFile.Type.ClasspathResource` 只负责编译产物的根目录归位，不新增 `CompileOutput.Type.ClasspathResource`。

## 实现方案

### 1. ClassLoader 早返回 hook

对 `java/lang/ClassLoader#getResource(String): URL` 使用 retransformation 注入以下等价逻辑：

```java
URL overlay = InstrumentationHooks.classLoaderGetResource(this, name);
if (overlay != null) {
    return overlay;
}
// Continue with the original implementation.
```

这是真正的 overlay-first：命中时不执行原方法；未命中或 hook 内部异常时返回 `null`，继续执行原实现。

hook 仅处理宿主应用 ClassLoader 及其子 ClassLoader。资源路径不做业务白名单，因为 `.overlay` 中的部署内容本身就是 Jugg 已确认的目标状态。

查找顺序：

1. `.overlay/base.apk/<name>` 是普通文件时，返回 `file:` URL。
2. `.overlay/base.apk/resource.ap_` 存在且 ZIP 中确实包含 `<name>` 时，返回 `jar:file:.../resource.ap_!/<name>` URL。
3. 否则返回 `null`，执行原始 `ClassLoader#getResource`。

`resource.ap_` 必须先检查 ZIP entry。仅判断 ZIP 文件存在会导致缺失条目返回不可用 URL，从而破坏原始 ClassLoader fallback。

日志策略：

- hook 首次进入只打印一次 logcat，确认重转换代码确实被执行。
- 每次命中 overlay 都打印 logcat，并区分 `file` 与 `resource_ap_` 来源。
- 不增加能力确认文件和自动安全降级；失败时保持原始行为，由日志辅助定位。

### 2. 部署后重启判断

不新增独立 restart flag，也不把资源类型继续透传到 `CompileOutput`、`DeployItem`。直接使用最终部署路径判断：只要 overlays 中存在 APK 根目录文件，就需要重启。

以下路径不属于 APK 根目录文件：

- `res/**`
- `assets/**`
- `resources.arsc`

除此以外均视为 APK 根目录文件，例如 `values/**`、`META-INF/**`、`files/**`。判断落在 `JuggDeployData.isNeedRestartApp`，历史恢复后的部署数据也自然复用同一规则。

部署成功后沿用现有 `DeployTargetManager.restartApp`，通过 `am start -S` 停止旧进程并启动新进程。

### 3. 版本与文档

- 提升 agent 版本，确保插件能识别并替换新的 JVMTI agent。
- 更新 JVMTI 知识库，记录 ClassLoader resource hook、作用域、fallback 和重启约束。

## Trade-off

### 路径启发式重启

优点：

- 不引入新的跨阶段类型和 flag，改动集中且历史部署数据天然兼容。
- 判断基于最终部署事实，而不是编译器实现细节。

代价：

- `AndroidManifest.xml`、部署 flag、`resource.ap_` 等根目录文件也会触发重启，存在可接受的无害 false positive。
- 如果 Classpath resource 刻意命名在 `res/**`、`assets/**` 或恰好叫 `resources.arsc`，会 false negative。Android APK 语义下这些名字已有专属含义，实际风险低。

### 重转换 ClassLoader

优点：

- 能覆盖 legacy Compose resource，也为其他常见 ClassLoader resource 提供统一能力。
- overlay-first 只在明确命中时早返回，未命中保持平台原行为。

代价：

- `java/lang/ClassLoader` 是平台核心类，字节码注入错误的影响面大。因此实现必须保持短路径、无状态依赖、异常 fail-open，并通过真机重转换验证。
- hook 方法会在所有 `getResource` 调用入口执行。宿主 ClassLoader 过滤能避免修改无关加载器的结果，但入口判断仍有少量固定开销。

### `resource.ap_` ZIP 查找

优点：

- 兼容只保留聚合资源包的部署方式，不要求额外保存独立文件。

代价：

- 每次查找需要短暂打开 ZIP 并确认 entry，命中路径有额外 I/O。这里不缓存 `ZipFile` 或查询结果，以避免增量部署后读取旧状态；resource lookup 频率通常远低于业务热路径。
- 返回的 `jar:file:` URL 可能被 Android `JarURLConnection` 缓存，因此部署成功后必须重启进程。

### Best-effort 策略

优点：实现简单，不增加能力握手、回滚和重新安装分支。

代价：如果特定 Android 版本拒绝重转换或 URL 读取失败，部署流程不会自动升级为完整安装；只能从重转换日志、首次进入日志和 overlay hit 日志定位。

## 非目标

- 不 hook Compose 内部资源缓存。
- 不根据 Kotlin 生成 class 判断是否重启。
- 不新增 `CompileOutput.Type.ClasspathResource` 或 `DeployItem` 资源类型。
- 不处理部署进行中读取半成品的并发窗口；部署成功后统一重启。

## 验证方案

1. L1：`JuggDeployDataTest`
   - APK 根目录 overlay 触发重启。
   - `res/**`、`assets/**`、`resources.arsc` 不单独触发重启。
2. Flow：`JuggDeployerHelperDeployFlowTest`
   - 根目录 overlay 部署成功后调用 `restartApp`。
3. 构建：编译 JVM TI agent 和插件相关 Kotlin 代码。
4. 真机：
   - 确认 `java/lang/ClassLoader` retransformation 成功。
   - 确认 hook 首次进入日志只出现一次。
   - 修改 `values/strings.xml` 后确认每次命中日志及新字符串。
   - 分别验证独立文件和 `resource.ap_` 条目路径；缺失条目时确认原始资源仍可读取。
   - 确认部署成功后的进程已重启，新进程读取新资源。
