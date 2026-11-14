# 远端源码同步：展开传输树之外的符号链接

## 问题与修复边界

Repo linkfile 可把工程内资源目录映射到工程外共享目录。旧 `rsync -av` 保留链接；只上传 Android 工程时，远端缺少链接目标。Gradle 若按目录是否存在决定加入 sourceSet，构建可能成功，但 APK 缺少物料。

实际链路为 `RemoteGradleCompileClient.compileAndFetchResult()` → `syncSourceFile()` → `RsyncSyncFileCommand` / `SyncFileCommand` → `SyncFileCommand.getRsyncArguments()`。只在最后的共用上传入口添加 `--copy-unsafe-links` 及英文注释：外部相对链接和绝对链接展开复制，传输树内的相对链接保留。未调整过滤顺序、删除策略、增量比较、源路径尾部 `/`、端点路径处理、下载参数或 Gradle sourceSet；未新增工程专用配置。

## 验证环境与失败证据

- macOS，实际插件调用 `/Users/wormchen/.jugg/resources/tools/darwin/rsync-3.4.1`，版本 **3.4.1 / protocol 32**。与仓库 `main/src/main/resources/tools/darwin/rsync-3.4.1` 的 SHA-256 一致。依据 JOJO 的 `compile_2026-10-14_19-19-56.0.log` 中 19:43:31 的源码上传命令确认。
- 隔离临时目录复现 `android/assets → ../.workspace/others/assets`：旧 `-av` 返回 0，目的端仍为符号链接，目标和 `assets/player` 不存在。
- 测试价值：真实目的端文件类型、内容、链接有效性和失败退出码是稳定可观察行为；只断言参数字符串不能证明本问题已修复。复用 `SyncFileCommandTest`，新增 L1 外部进程/文件系统回归，通过生产 `RsyncSyncFileCommand` 执行本地端点，无 SSH mock 或生产测试 seam。空格端点按现有命令接口传入带引号的 shell 参数，未扩大为原始未转义端点的路径修复。
- 修复前运行该 owner 的 12 项测试：8 项通过，4 项失败；失败项为外部目录、外部/绝对文件链接、缺失目标和不可读目标，原同步行为通过。
- 首次默认 JDK 21 运行被无关 `:jvmti_agent:buildInstrumentJar` D8 错误阻断；使用已安装 JDK 17 执行定向测试，完成红/绿验证，无跳过构建任务。

## 修复后实际结果

| 场景 | 结果 |
|---|---|
| 无符号链接 | 普通内容正确；固定 include/exclude、自定义排除、过期文件删除和增量更新通过；未变文件的目的端修改时间不变 |
| 内部相对目录链接 | 仍为原相对链接，目标有效；子目录中的 `../shared` 没有被误判为工程外 |
| 外部相对目录链接 | 目的端成为真实目录，嵌套及隐藏文件完整；已有悬空链接能被替换；本地链接和源内容不变 |
| 外部相对文件链接 | 目的端成为真实文件，内容一致，本地链接及源内容不变 |
| 绝对路径链接 | 工程外和工程内的绝对文件链接都展开；真实 JOJO 资源的绝对目录映射也已隔离验证 |
| 多工程传输根 | 把 Android 与共享目录一起纳入传输树时，跨工程的相对链接仍保留且有效 |
| 路径含空格 | 源/目的目录、链接名和链接目标含空格的命令执行成功 |
| 展开目标不存在 | 返回 rsync 23，保留 `symlink has no referent` 和出错链接名；命令完成标记保留失败码 |
| 展开目标不可读 | 以当前非 root 用户读取 mode-000 文件，返回 rsync 23，保留 `Permission denied` 和文件名；验证后恢复夹具权限 |

执行命令：

```bash
JAVA_HOME=/Users/wormchen/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
  ./gradlew :main:test \
  --tests com.sickworm.intellij.jugg.gradle.compile.SyncFileCommandTest \
  --tests com.sickworm.intellij.jugg.gradle.compile.BaseSshCommandTest \
  --tests com.sickworm.intellij.jugg.gradle.compile.FetchClasspathCommandTest
```

21 项测试全部通过（12 项源码同步、3 项退出码解析、6 项产物拉取参数），无失败、错误或跳过；同时编译本次生产和测试 Kotlin。`BaseSshCommandTest` 保护完成标记中的真实失败码；`FetchClasspathCommandTest` 验证下载侧原有过滤行为。同步调整未修改 compile/deploy 编排，不执行设备 Flow。

## JOJO 及未执行项

- 只读检查真实 `android/assets` 链接和 `app/build.gradle.kts`：只有 `assets/player` 存在时才向 debug 添加根目录 `assets`，release 未配置该目录。
- 在隔离目录用实际 rsync 展开真实共享资源；目的端 `assets/player` 存在且为实际内容，全部 **3 个文件**的相对路径和 SHA-256 与本地源一致。复制前后再次核对真实共享资源哈希和 Repo 链接目标，均未变化。
- 非交互 SSH 认证返回 `Permission denied`（exit 255），无法执行真实 JOJO 远端 `mergeDebugAssets`、debug/release APK 打包和共享目录缺失时的 Gradle 回归。debug-only 与缺失目录条件只核对现有配置，未将配置检查报告为产物验证。未操作或重启用户设备，未移动真实共享目录。
- 本地回归没有启动 SSH 传输或 iFT 客户端；两种上传共用参数入口已通过 IDE 引用定位核对。

## 文档与交付

同步 `04_engineering_project.md` 中上传语义与失败边界，并在现有中英文 `troubleshooting/remote-build-failed.md` 补充“构建成功但映射资源缺失”的排查步骤。Wiki production build、语言镜像和链接检查通过；不改路由或导航。

仅提交本次代码、回归与文档；不推送、不创建 PR、不合并，保留工作区其他改动。
