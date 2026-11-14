# Jugg Gradle 无感安装方案

## 目标

提供一个独立 Gradle script。Android Studio Sync 时异步检查并首次安装 Jugg，不阻塞 Sync 或后续编译，不修改业务工程的 Jugg 配置。

脚本由 `jugg_backend` 仓库维护，业务工程只负责通过 `apply from:` 引入。

## 已确认行为

1. 仅在 macOS 的 Android Studio Sync 中触发；普通 Gradle 构建不触发。
2. Groovy 入口先定位发起本次 Sync 的 Android Studio，并读取插件 JAR 中的 `META-INF/plugin.xml` 校验当前 Jugg plugin ID `com.sickworm.jugg`；已安装时不创建运行目录、不写日志、不启动后台进程。前置检测无法定位时，worker 会在初始化任何日志和状态文件前再次检查，已安装同样直接退出且不写任何日志。
3. 未安装或无法可靠定位当前 IDE 时，Gradle 只启动独立后台进程，不等待下载和安装完成；bootstrap 失败仅写本地日志，不输出 Gradle warning，也不改变 Sync 或编译结果。
4. 项目标识复用 `JuggServer` 语义：优先取向上找到的 Git 仓库 remote URL 最后一段并移除 `.git`，无法取得时回退 `rootProject.name`。
5. 目标 Android Studio 优先匹配本次 Sync 的 `android.studio.version`；无法匹配时选择运行中的版本，否则选择已安装的最新版本。
6. 只处理当前插件的首次安装。目标 IDE 已存在 plugin ID `com.sickworm.jugg` 时退出；历史 plugin ID 不视为已安装。
7. 从 `/download_for_project?project=<project>` 下载服务端为该项目选择的插件包。
8. 强制验证 HTTP 状态、`Content-Type`、`Content-Disposition`、`X-Jugg-SHA256`、文件 SHA-256、ZIP 完整性、顶层 `jugg/` 目录和插件 ID。
9. 解压与校验在目标插件目录下的临时目录完成，成功后原子移动到 `plugins/jugg`；失败不产生正式安装目录。
10. 同一 Android Studio 使用独立安装锁，避免多个工程同时 Sync 重复安装。
11. 安装成功后通过 macOS 系统通知提示“重启 Android Studio 后生效”。首次安装不支持无重启生效。

当前下载地址为内网 HTTP，SHA-256 只用于发现传输或存储损坏，不能证明包来源；现阶段接受可信内网这一安全前提，后续应升级 HTTPS 或增加独立签名校验。

## 变更范围

- 新增 `../jugg_backend/gradle/jugg_plugin_bootstrap.gradle`：自包含的 Sync 检测、后台启动、IDE 定位、下载、校验、安装、日志与通知逻辑。
- 运行数据统一保存到 `~/.jugg/gradle-install-plugin/`。
- 不修改 Jugg 插件初始化、热更新、Run Configuration 或后端下载接口。
- 不包含 Windows、Linux、IntelliJ IDEA、旧插件迁移和自动重启 Android Studio。

## 验证

- Groovy script 可被最小 Gradle Settings 工程加载。
- 普通 Gradle invocation 不创建安装任务。
- 模拟 Sync invocation 能快速结束，安装工作进入独立日志。
- 当前机器已安装 Jugg 时，后台进程正确识别目标 IDE 并退出。
- 独立验证 `/download_for_project?project=jugg` 的响应头、SHA-256、ZIP 完整性与插件 ID。
- 审查脚本中的临时目录、锁和目标目录边界，确保失败不会覆盖现有插件。
