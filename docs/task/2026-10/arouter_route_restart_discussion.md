# ARouter 路由变更后的重启判定：最小实施方案

## 问题与现场证据

`aigc-bot-client` 的 `JavaRouteActivity` 将 `@Route` 路径改为 `/demo/java4`。完整 Gradle 构建生成的 `ARouter$$Group$$demo.java` 仍含 `/demo/java3`，本轮 Jugg 增量生成物已含 `/demo/java4`。2026-10-10 11:39 的 `compile_latest.log` 显示：生成的 Group 类被判为 `isCanHotReload=true`，部署发送 `RESTART_ACTIVITY_REQUEST`，随后记录 `App foreground, no need to restart app.`，最终为 `HOT_RELOAD`。日志路径：`/Users/wormchen/IdeaProjects/demo/aigc-bot-client/build/jugg/log/compile_latest.log`，关键位置约为 995、1135、1158、1169 行。

附件 ARouter runtime 的 `Warehouse.routes`、`groupsIndex` 是进程内静态 Map。类可被 JVMTI 重定义，只证明字节码结构允许热替换，不证明已加载的路由映射会刷新；重建 Activity 也不会重建这些进程级缓存。这是当前重启判定缺口。

## 本轮提出但未采纳的方案

曾提出由 ARouter 适配器对生成的路由索引附加通用“内容变化后重启进程”提示；让提示经过 `CompileFile`、`CompileOutput`、`ChangedFile` 进入 `DeployDataPlanner` 和 `JuggDeployData.isNeedRestartApp`。为覆盖部署失败、IDE 重开、完整构建与 reinstall recover，又提出在部署历史中保存上次**成功部署**的生成索引指纹，并在每次规划部署时重新比较。

该方案涉及通用编译元数据传递、部署数据、部署历史格式及恢复流程。用户认为改动过大，未采纳。

## 已确认的实施边界

- 在独立文件 `deploy/run/ArtifactRestartPolicy.kt` 中用内部扩展函数集中按产物判断的进程重启规则，`JuggDeployData` 保留调用入口。规则与私有黑名单放在同一文件，便于后续增加适配，保留 hot-fix class、Flutter JIT runtime、NativeLib 与 APK 根目录 overlay 的既有行为。
- 使用固定的 `HOT_RELOAD_CLASS_BLACKLIST` 识别 ARouter `Group`、`Root`、`Providers`、`Interceptors` 注册索引，按解析后的 class 简名前缀匹配，兼容标准包与定制包。新增和已有索引都要求重启；普通路由类和 Autowired helper 本身不命中。
- 接受保守重启：索引进入本轮 payload 就要求重启，不增加内容去重或成功部署指纹。当前适配器在处理器缓存命中时仍返回生成源码，因此只改普通方法体但同轮带入注册索引时也会重启。这是相对早期讨论中“普通方法体修改始终热重载”目标的明确收缩。
- 从实际 payload 重新判断，沿用 APK 过滤、retry 与历史重放流程。不增加 compiler 传递字段、历史格式或“曾经 HOT_FIX”标记；已有 `isRecoverReplayAfterReinstall` 保持不变。
- legacy Compose resource 归入 APK 根目录规则。现代 Compose resource 的路径来自 Gradle `packagingDir`，没有固定前缀限制，保留 `isComposeResourceCompiled`，避免仅按常见目录匹配而缩窄支持范围。
- 不修改 ARouter 生成器、class 结构比较器或 runtime 缓存，不新增公共扩展接口。

## 验证

- 自动化测试价值：重启策略影响进程状态、APK 归属与部署结果，属于稳定的可观察行为；在已有 `JuggDeployDataTest`（L1）追加用例，不引入测试专用生产接口。
- 失败证据：修改生产代码前，定向运行 21 个 `JuggDeployDataTest` 用例，2 个新增 ARouter 用例失败，普通类与存量场景通过。
- 环境：默认 JDK 21 在 `:jvmti_agent:buildInstrumentJar` 被 D8 空指针阻断；切换本机 Corretto 17 后正常执行到预期断言。
- 修复后：同组 21 个用例全部通过；`JuggDeployerHelperDeployFlowTest`（L2 虚拟设备 Flow）35 个用例全部通过，覆盖既有 Compose、APK 根目录资源、Flutter、NativeLib 和 reinstall replay 重启行为。此次不修改部署编排，复用该 Flow 验证既有产物重启规则集中后的行为。
- Flow 首次初始化因缺少 `ANDROID_HOME` 失败；补齐本机 SDK 路径后重试通过。复用既有 demo APK 与项目信息，临时 `skip_assemble` 在结束后恢复为不存在。
- 执行命令：Corretto 17 下运行 `./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.run.JuggDeployDataTest'`；设置 `ANDROID_HOME` 后运行 `./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest'`。IDE 生产代码编译随 Flow 测试通过。
- 文档验证：Wiki 中英文镜像及链接检查、production build、四个修改页面的 HTML 内容检查、`git diff --check` 均通过。未执行真机 ARouter 路由跳转验收。
- 独立文件整理：仅将既有规则与黑名单移入 `ArtifactRestartPolicy.kt`，不改变部署编排或行为；沿用已有回归 owner，不新增测试，整理后重新运行 21 个 `JuggDeployDataTest` 用例全部通过。
