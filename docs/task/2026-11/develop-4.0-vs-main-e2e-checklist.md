# develop/4.0 对比 main：端到端自测清单

Purpose: Provide a manual end-to-end acceptance matrix for the committed develop/4.0 changes relative to local main.
Decision: Cover branch changes, shared-flow regressions, conditional environments, and main-only CI gaps separately. All cases start unexecuted.
Impact: Documentation only; no product, configuration, or test-environment changes.

## 1. 比较范围与使用方法

- 编写日期：2026-11-15。
- 被测分支：`develop/4.0`，`50498f7fd`。
- 对照分支：**本地** `main`，`95b7b5bd4`；本次未 fetch。
- 共同祖先：`989b33401473cb1e9098aea43b4fd0a0d69960ab`。
- 新增/改动范围用 `git diff main...50498f7fd`；两端最终差异用 `git diff 95b7b5bd4 50498f7fd --` 交叉核对。后者为 1,388 个文件，118,641 行新增、20,711 行删除，包含 AAPT2 源码导入、路径搬迁和文档翻译，不能直接当成功能数量。
- 排除当前未提交的 Manifest、style 探针及其调查文档。
- `main` 独有 `836aa0a6d`、`95b7b5bd4` 两个 CI 提交，见 §20；不是本分支已实现能力。
- 本文是**待执行清单**，没有在本次任务中运行 IDE、设备、构建或故障注入。历史任务记录的通过结果不自动计入本轮。

标记：**P0** 为主链路必测，**P1** 为完整回归，**P2** 为特殊环境/故障注入。带条件的项目仅在具备该条件时执行；不具备时记录 `SKIP + 原因`，不要勾为通过。`[D]` 表示直接差异，`[R]` 表示共享代码搬迁/编排带来的回归保护。每项“操作 → 预期”即判定标准。

共 **218 项**：P0 38 项、P1 161 项、P2 19 项；其中 GAP-01 是范围决策，GAP-02/03 是合入 main 独有能力后的待补测项。

所有成功部署都检查：终态成功、设备实际运行新逻辑/资源、无新增崩溃、下一轮改动仍可部署。所有失败都检查：真实原因可见、未虚报成功、修复原因后可恢复。记录实际是否执行 Gradle、是否安装、PID/Activity 是否变化；不要仅以 CLI 退出码判定设备结果。

## 2. 准备环境与证据

准备测试 App A：Java/Kotlin、文本按钮、XML、string、drawable、assets、可持久化计数器，以及可显示的 `BUILD_MARKER`。另准备多模块工程 B；按业务需要补充 Compose/KMP、DataBinding、androidTest、native SO、release/minify、远程构建工程。使用独立测试副本，先验证 main 基线可以构建和运行。

| 维度 | 本轮填写 | 覆盖要求 |
|---|---|---|
| 插件/CLI/Runtime | 版本、构建标识、实际加载来源 | 分清插件版本与独立 Runtime 版本 |
| 主机 | OS、CPU、JDK、Python、SDK、Gradle、AGP | 主力平台必测；跨平台发布补 Windows/Linux/macOS |
| IDE | Android Studio 完整版本 | 主力版本必测；旧版和 Quail/Rabbit 等适配边界选测 |
| 设备 | serial、API、ABI、root/run-as 能力 | 至少一台真机/模拟器；多设备、兼容模式另列 |
| 工程 | 路径、commit、variant、applicationId | A/B 分别记录，使用同一可复现标记 |
| 存储 | 实际全局目录、settings 备份位置 | 迁移、损坏注入用可丢弃环境 |
| 执行记录 | 用例 ID、PASS/FAIL/SKIP、实际结果、日志/截图 | 未执行保持复选框为空 |

默认命令以工程根目录执行。强制 Host 示例：`jugg --runtime idea status`、`jugg --runtime standalone status`；精确设备示例：`jugg --runtime standalone --serial SERIAL deploy`。真实参数以本构建 `jugg help <command>` 为准。重复矩阵分别记录结果，不用一项通过代替所有 Host。

## 3. 安装、升级与启动 [D]

- [ ] INS-01 **P0** 从 main 插件升级当前 ZIP，打开已有工程、Sync、Run → 插件正常加载，旧配置可用，App 启动。
- [ ] INS-02 **P0** 从 IDE 安装 CLI/Standalone，关闭 IDE 后执行 `jugg version`、`status`、`deploy` → 可独立启动并完成真实部署。
- [ ] INS-03 **P0** 用完整 standalone Bundle 在无 IDE 环境安装，执行首次 `gradle-build` → 无缺失 IDEA 类/JAR，安装并启动 App。
- [ ] INS-04 **P1** 清洁用户环境安装插件附带的精简 Bundle → 从插件补齐共享 JAR 后独立启动成功。
- [ ] INS-05 **P1** 重复安装同一构建，再安装不同构建 → 重复操作可用；更新后新调用使用新 Runtime，旧 daemon 被停止。
- [ ] INS-06 **P1** IDEA 热更新后重装插件、重开工程 → 不出现 linkage 错误；CLI 发现和独立 Runtime 均可用。
- [ ] INS-07 **P1** 更换有效 `JAVA_HOME`，随后移除它使用 PATH JDK → 启动按当前环境选择 JDK；缺少可用完整 JDK 时明确失败。
- [ ] INS-08 **P1** 首选 Python 不可用或低于 3.7，提供后续兼容解释器 → wrapper 可选择兼容解释器；全部不可用时说明原因。
- [ ] INS-09 **P1** 无 `ANDROID_HOME`，工程只有有效 `local.properties/sdk.dir` → 独立构建/部署成功；SDK 全部缺失时错误清楚。
- [ ] INS-10 **P1** 工程/SDK/全局目录含空格和中文 → CLI 启动、构建、安装、日志路径均正确。
- [ ] INS-11 **P2** 在隔离安装包破坏 manifest/JAR 校验值或移除必需文件 → 安装拒绝残缺包；已安装可用版本不被半成品覆盖。
- [ ] INS-12 **P2** 制造 Runtime 启动失败或慢启动 → 显示进度与启动日志路径；超时失败可定位，修复后能重新启动。
- [ ] INS-13 **P2 / POSIX** 低文件句柄软限制下启动大工程 → launcher 按硬限制提升软限制；仍不足时有真实错误而非永久卡住。
- [ ] INS-14 **P1 / Windows** 安装、更新后从新终端执行 `jugg` → 用户 PATH 生效，运行不反复弹控制台，Bundle manifest 可读取。

## 4. CLI 路由、多工程与生命周期 [D]

- [ ] ROUTE-01 **P0** IDEA 打开 A，同时存在 standalone A，自动执行 `status/deploy` → 选择拥有 A 的 IDEA Runtime。
- [ ] ROUTE-02 **P0** 关闭 A 的 IDEA 后自动执行 `deploy` → 转至 standalone；再次打开 A 后自动调用回到 IDEA。
- [ ] ROUTE-03 **P0** 分别指定 `--runtime idea/standalone` → 确实使用指定 Host；IDEA 不可用时明确失败。
- [ ] ROUTE-04 **P0** IDE 全关，依次对 A、B 执行命令 → 共享 daemon，工程状态、日志、APK、设备包名不串用。
- [ ] ROUTE-05 **P1** 父工程在 IDE 打开，进入含独立 settings.gradle 的嵌套工程 → 默认识别内层 Gradle 根，不误送父工程。
- [ ] ROUTE-06 **P1** 从 A 子目录自动调用，再显式 `--project-dir A` → 均命中预期工程；显式子路径的父工程匹配与提示一致。
- [ ] ROUTE-07 **P1 / macOS** 用大小写不同或符号链接路径引用同一工程 → 不重复初始化，展示路径可辨识。
- [ ] ROUTE-08 **P1** 两个终端同时首次请求同一未启动工程 → 只形成一个有效启动/工程注册，不重复构建初始化或争用端口失败。
- [ ] ROUTE-09 **P1** 从普通非工程目录运行 help 与 settings → help 不探测 Runtime；settings 不把当前目录初始化成工程。
- [ ] ROUTE-10 **P1** 不存在路径、缺 projectDir、未知工具、错误 JSON-RPC → 返回对应参数/协议错误，不新建项目缓存、不抛裸 HTTP 500。
- [ ] ROUTE-11 **P0** A/B 已注册后执行 `jugg stop` → 同一全局根下 standalone 全停；再次调用恢复，历史和日志保留。
- [ ] ROUTE-12 **P1** 执行 `jugg --project-dir A stop` → 仍是全局 standalone 停止；`--runtime idea stop` 明确拒绝。
- [ ] ROUTE-13 **P2** 空闲达到默认 4 小时，与持续长任务分别观察 → 真正空闲才退出；任务进行中不因空闲计时被杀。

## 5. Run Configuration 与项目模型 [D]

- [ ] CFG-01 **P0** 新工程首次 Sync → 自动生成可运行 Jugg 配置，任务和 APK 对应当前 app/variant。
- [ ] CFG-02 **P1** 根目录即 application 模块 → 正确生成根模块任务，不要求存在 `:app`。
- [ ] CFG-03 **P1** 模块路径含点号（如 `:zxphone5.0`）、多层模块、included build → 保留真实 Gradle 路径，不把点号改成冒号。
- [ ] CFG-04 **P1** 多 app/同名末级模块，逐个选配置运行 → 正确构建/安装目标，不因显示名相同混淆。
- [ ] CFG-05 **P1** 切换 variant 后 Sync → 唯一、未自定义的对应配置可跟随；不会反复创建重复配置。
- [ ] CFG-06 **P0** 自定义命令、APK 路径、名称与 remote 参数后 Sync/重开 → 用户设置和当前选择保持。
- [ ] CFG-07 **P1** 选普通 Android 配置或自定义 Jugg 配置后 Sync → 不被无关默认配置强行替换。
- [ ] CFG-08 **P1** 项目信息读取失败/暂时无 application 元数据 → 已有可运行配置仍在；恢复 Sync 后补齐缺失项。
- [ ] CFG-09 **P1** 配置集中 build 输出目录 → 新默认配置找到实际 APK；已有自定义路径保持。
- [ ] CFG-10 **P0** IDE 修改/选择配置，关闭 IDE 后 standalone `gradle-build` → 使用同一任务、variant、APK 和 remote 参数。
- [ ] CFG-11 **P1** standalone 先建立默认配置，再打开 IDE → 正确导入共享配置，改名后 UUID/选中关系稳定。
- [ ] CFG-12 **P1** A 成功远程全量构建后打开全新 B → 新配置继承默认远程参数，但 B 的任务、APK 和身份仍属于 B。
- [ ] CFG-13 **P1** 已有 B 配置后再更改全局默认模板 → B 原配置不被覆盖；新建配置使用新模板。
- [ ] CFG-14 **P1** Sync/后台项目模型刷新中点击 Run、取消再 Run → IDE 可交互，无 UI 冻结或永久等待。

## 6. 编译、部署主链路 [D/R]

本节 P0 用例在 IDEA 手动 Run、强制 IDEA CLI、强制 standalone CLI 三条入口分别执行；`compile` 的语义项仅对 CLI 执行。每轮先建立相同的完整 Gradle 基线。

- [ ] FLOW-01 **P0** 无基线首次 Run/`deploy` → 按现有策略建立基线并安装启动；再改一个方法可以增量。
- [ ] FLOW-02 **P0** 已有基线修改 Java 返回值、Kotlin 返回值各一轮 → 新值在设备可见，正常增量不无故全量构建。
- [ ] FLOW-03 **P0** 修改代码后只执行 `compile` → 编译成功但设备仍旧值；随后 `deploy` 才见新值。
- [ ] FLOW-04 **P0** `gradle-build` → 不仅编译成功，还安装/启动新 APK；下一轮 `deploy` 正常。
- [ ] FLOW-05 **P0** 无源码变化但设备卸载 App，然后 `deploy` → 执行必要安装，不能以“未编译源码”跳过部署。
- [ ] FLOW-06 **P0** 无任何变化、App 已安装时再次 Run/`deploy` → 结果与实际操作一致，正常收尾，不卡住。
- [ ] FLOW-07 **P0** 输入 Java/Kotlin 语法错误 → 明确编译失败，不发布旧产物冒充成功；修复后增量部署成功。
- [ ] FLOW-08 **P1** 新增类、字段、方法并从页面调用 → 结构变化按适用模式部署，App 不缺类/缺方法。
- [ ] FLOW-09 **P1** 改跨模块常量、父类方法、Kotlin inline 调用 → 依赖调用点体现新值，传播范围与日志相符。
- [ ] FLOW-10 **P1** 修改 Manifest 中可见行为（如 Activity label），Run → 编译与必要重启/安装完成，新行为生效。
- [ ] FLOW-11 **P1** 修改 build.gradle/依赖版本 → 变更识别及确认/全量路径正确；构建成功后可继续增量。
- [ ] FLOW-12 **P1** 设置低文件/模块阈值并触发超限，分别测试确认、取消、关闭询问 → 行为符合选项，无无限弹窗；结束恢复原阈值。
- [ ] FLOW-13 **P0** Run 中取消，修正/再次运行 → 任务和锁释放，待部署改动可重试。
- [ ] FLOW-14 **P1** 部署故障且启用自动 Gradle fallback → 实际执行恢复链，最终状态反映恢复结果；关闭开关后失败清楚且可手工 Force Gradle。
- [ ] FLOW-15 **P1** `--always-restart-app true/false` 对比可热更新的方法体 → true 重启；false 在适用场景保留进程/页面状态。
- [ ] FLOW-16 **P1** IDE Debug 首次和增量后分别命中断点 → Debug session、目标进程正确，改动后断点仍可用。
- [ ] FLOW-17 **P1** IDEA Compile on save 开启后保存 → 触发编译；standalone 保存文件不会因该选项自动运行编译任务。
- [ ] FLOW-18 **P1** 删除源文件/资源后执行增量，再执行完整重装 → 记录增量删除能力边界；最终重装确实清除旧内容，不把旧 overlay 留存误写成删除成功。

## 7. 文件检测与 Host 交接 [D]

- [ ] STATE-01 **P0** IDEA 部署 A1 → standalone 部署 A2 → IDEA 部署 A3 → 每次设备呈现对应版本，无旧 APK/旧模型覆盖。
- [ ] STATE-02 **P0** standalone 全量构建后 IDEA 直接增量 → 正确恢复基线，无长期等待或反复重建 source DB。
- [ ] STATE-03 **P1** 两 Host 交替修改依赖/variant 并全量构建 → 下一个 Host 使用新模型、APK、classpath 和部署历史。
- [ ] STATE-04 **P0** 外部编辑器/终端修改文件，再 `status --refresh-changes true`、`deploy` → 改动被捕获并生效。
- [ ] STATE-05 **P1** 新建目录后创建源文件/资源 → standalone watcher 能发现；build 输出不被误当作源码输入。
- [ ] STATE-06 **P1** 修改后还原到原内容 → 未编译内容从待处理列表正确消退；手工选择项按其显式选择保留。
- [ ] STATE-07 **P1** 在测试分支切换 commit/cherry-pick/pull 引入修改 → Git 补偿识别实际差异，部署新内容。
- [ ] STATE-08 **P1** 关闭 Runtime 时修改文件，重新启动 → 不依赖上一次会话 watcher 也能找回变更。
- [ ] STATE-09 **P1** 工程引用根目录外模块，修改该模块 → IDE/standalone 正确捕获，模块自身 build 目录仍排除。
- [ ] STATE-10 **P2** 制造 WatchService 启动失败/overflow → 有诊断，后续 Git/status 补偿仍能发现可恢复改动。
- [ ] STATE-11 **P1** 同一工程 IDE 和 standalone 同时部署 → 项目写入串行化，数据库/历史不损坏；等待结束可继续。
- [ ] STATE-12 **P1** A 长任务期间查询 A status，同时操作 B → status 不因项目锁长期阻塞，B 不被 A 的长任务占住全局资源锁。
- [ ] STATE-13 **P2** 隔离环境终止持锁 Runtime，再从另一 Host 运行 → OS 锁释放并恢复；错误信息可定位锁拥有者。
- [ ] STATE-14 **P1** 重启 IDE/daemon 后连续两次增量 → 缓存可恢复，不每次重新做全项目源索引。
- [ ] STATE-15 **P2** 在测试副本损坏 ConstRef SQLite → 损坏恢复有界，主 Run 不被辅助分析异常取消；恢复后再次改常量验证实际调用结果。

## 8. 全局 Settings CLI 与跨进程生效 [D]

- [ ] SET-01 **P0** `jugg settings`、`settings list`、`settings get compileOnSave` → 返回真实设置；list 含 key/value/description。
- [ ] SET-02 **P0** `settings set compileOnSave false`，IDE 刷新查看，再从 IDE 改回 → 两端一致，重启仍保存。
- [ ] SET-03 **P1** 两个进程先后改不同设置 → 两项均保留，不被旧内存快照覆盖。
- [ ] SET-04 **P1** 对相同值重复 set、再 reset → changed 结果正确；reset 回默认值，重启后一致。
- [ ] SET-05 **P1** 未知 key、只读 key、布尔非法值、Int/Long 溢出、畸形 JSON → 非零退出且说明字段，原配置不变。
- [ ] SET-06 **P1** 设置支持 null 的项并 get/JSON 输出 → null 显式保留，字符串值与空值区分正确。
- [ ] SET-07 **P0** 修改 Backup classpath，不传 confirm、传 false、传 true 各一次 → 前两次保留原值；确认后生效。
- [ ] SET-08 **P1** 启用 Embed changes into APK → IDE 取消/确认和 CLI `--confirm` 结果一致，RemoteViews 条件下验证实际效果。
- [ ] SET-09 **P1** 配置 Custom Server → 需确认；URL 与信任模式一起保存；清空恢复自动选择逻辑。
- [ ] SET-10 **P2** 自动选服进行中手动设置 serverUrl → 后台结果不覆盖更新的手动选择。
- [ ] SET-11 **P1** 写入部分合法 `defaultCompileSettings` JSON → 缺省字段补默认；新建配置使用模板。
- [ ] SET-12 **P1** 模板含测试密码/环境变量 → list/get/错误/日志不回显秘密；回写 `<redacted>` 占位符被拒绝。
- [ ] SET-13 **P1** 模板无效端口、syncMode、字段类型 → 错误指向字段，原模板仍可用。
- [ ] SET-14 **P1** A/B 已有基线，修改 Backup classpath，分别从 IDE/standalone 运行 → 两工程在下次使用前各应用失效要求，完成后恢复增量。
- [ ] SET-15 **P1** 修改兼容设备记录或 native 开关 → 下一次需要的重装确实发生；取消/失败后要求保留，成功后不每轮重装。
- [ ] SET-16 **P1** 对无需失效的普通设置修改/不修改任何设置重启 → 保留有效基线和正常增量收益。
- [ ] SET-17 **P2** settings 文件损坏/不可写 → 编辑明确失败，保留原文件/有效状态；恢复文件后可继续。
- [ ] SET-18 **P1** 从旧版迁移设置，同时 JSON 已有部分同名值 → 仅补缺失字段，不覆盖新值。
- [ ] SET-19 **P1** 普通目录、无项目、无 daemon 时执行 settings → 自动启动零项目 Runtime，不生成该目录 build/jugg。
- [ ] SET-20 **P1** settings 带 `--serial`/`--if-compiling`，或连不支持 settings 的旧 Runtime → 参数拒绝/升级提示明确。
- [ ] SET-21 **P1** 保存 deployOnSave 后观察 → 仅持久化，当前实现没有独立自动部署消费者；不能作为自动部署通过项。

## 9. 全局目录迁移 [D]

在可丢弃用户环境执行；迁移成功重启会删除旧目录。先记录配置、历史、CLI/Hook 路径，确保备份可恢复。

- [ ] MIG-01 **P0** IDE 将默认全局目录迁移到空目录并按提示重启 → 新根生效，设置/日志/资源/Runtime 保留，旧根按流程清理。
- [ ] MIG-02 **P0** 迁移后 IDE Run、独立 CLI deploy、settings 各执行一次 → 全部使用新目录并成功，无旧绝对路径残留导致失败。
- [ ] MIG-03 **P1** 已安装 Agent hooks/skills 后迁移 → 管理的 launcher、符号链接、Hook/Skill 引用修复，实际触发仍可用。
- [ ] MIG-04 **P1** 目标是非空目录、源的父/子目录、源本身、不可写目录分别尝试 → 明确拒绝，源与定位文件不被破坏。
- [ ] MIG-05 **P1** 同根运行两个独立 Studio 进程尝试迁移 → 提示关闭另一个；只关闭其工程但不退出进程仍不能绕过。
- [ ] MIG-06 **P1** 存在 standalone daemon 时迁移重启 → 旧根 daemon 停止，新启动读取新根。
- [ ] MIG-07 **P1** 新目录含空格/中文、原根有内部符号链接 → launcher 可执行，内部绝对链接指向新根，外部链接保留语义。
- [ ] MIG-08 **P2** 拷贝/校验阶段注入磁盘满、缺 JAR 或读失败 → 不发布新定位，不删除有效旧根；错误清楚。
- [ ] MIG-09 **P2** 重启后注入 launcher 修复/旧根清理失败 → 迁移标记保留，排除原因后下次启动可完成。
- [ ] MIG-10 **P1** `.juggdir` 缺失、畸形或不可读 → 按默认根规则启动；合法绝对路径时 JVM、Bootstrap、CLI、hooks 一致。
- [ ] MIG-11 **P2** 显式 `jugg.root.dir` 启动 → 保持进程覆盖语义，IDE 迁移入口拒绝该模式。
- [ ] MIG-12 **P1** 完成迁移再迁移到另一个空目录 → 二次迁移可用，数据库和设置可读。

## 10. 设备选择、安装及降级 [D/R]

- [ ] DEV-01 **P0** 单在线设备执行 devices/status/deploy/restart → 设备 online、App 包名正确，restart 后可验证进程结果。
- [ ] DEV-02 **P0** 两台设备且 `--serial A` → 只操作 A；下一条不带 serial 的请求不继承本次覆盖。
- [ ] DEV-03 **P1** IDEA 选 B、CLI 显式 A；standalone 环境 `ANDROID_SERIAL=B`、显式 A → 两种均以本次 A 为准，不改持久选择。
- [ ] DEV-04 **P1** offline/unauthorized/不存在 serial → 明确设备错误，无错误设备被安装。
- [ ] DEV-05 **P1** standalone 两台在线设备无 serial 部署/重启 → 按多目标流程执行；一台失败时结果如实展示各设备情况。
- [ ] DEV-06 **P1** 多设备无 serial 调用单设备 UI/log 工具（IDEA） → 返回多设备歧义错误，而非 HTTP 500；指定后可用。
- [ ] DEV-07 **P1** 无设备执行 compile 和 gradle-build → compile 可成功；gradle-build 可编译成功但部署失败，终态不混淆两者。
- [ ] DEV-08 **P1** 设备断连后重连重试 → 已知短暂故障可恢复；持续离线最终退出，后续任务不受锁残留影响。
- [ ] DEV-09 **P1** IDE 选中未启动 AVD，另有在线真机 → 不静默改装真机，目标解析符合 IDE 选择。
- [ ] DEV-10 **P0** 安装较高 versionCode 并保存计数器，再 IDE Run 低版本选择 **Allow downgrade** → 支持降级的测试 App 上旧 APK 安装成功，计数器保留。
- [ ] DEV-11 **P1** 同场景选择 Cancel → 原 App/数据保留；选择卸载重装 → 清数据安装低版本。
- [ ] DEV-12 **P1** 低版本通过 CLI/MCP 安装 → 按当前交互策略卸载重装并清数据；CLI 没有保数据降级开关。
- [ ] DEV-13 **P1** 普通升级、同版本覆盖、签名不同分别安装 → 正常路径保持，签名失败不被当成版本降级。
- [ ] DEV-14 **P1** 自定义安装脚本场景部署低版本 → 安装参数由脚本负责；失败真实上报，不被默认安装器偷偷改写策略。
- [ ] DEV-15 **P1 / IDEA** `clean-reinstall` → 数据确实清空，重装成功；standalone 调用得到能力不支持提示。

## 11. AAPT2、资源增量与新二进制 [D/R]

源码历史导入不意味着历史中的全部功能都是本次新增。本节重点验收新预编译产物、仅重链接本轮输入的修复，再保护旧资源能力。

- [ ] RES-01 **P0** 基线后依次修改 string、color、dimen、layout、drawable → 每轮设备显示新值，连续增量成功。
- [ ] RES-02 **P1** 新增资源并从代码引用 → R 生成/编译成功，资源 ID 可用；已有资源 ID 不无故改变。
- [ ] RES-03 **P0** 准备具有无法重新解析的历史二进制引用的已构建 APK，仅改无关 string/layout → 不再因无关基线引用失败，新资源生效。
- [ ] RES-04 **P0** 在本轮新输入中主动引用不存在资源 → 仍失败并指出问题；修正引用后再次增量成功。
- [ ] RES-05 **P1** 修改一个 style item，包含本地 attr、framework attr、parent 的不同 fixture → 新值生效，未修改 baseline 值不被误重新校验。
- [ ] RES-06 **P1** 同资源存在默认、night、v31 等配置，分别改其中一项 → 对应配置下新值生效，其他配置保留。
- [ ] RES-07 **P1** 连续改 A、改 B、再改 A，间隔一次资源编译失败并修复 → daemon 恢复后结果正确，无失败轮残留污染。
- [ ] RES-08 **P1** 多 source root 的同名 values/layout、不同 module 同名资源 → 按实际优先级/归属生效，不报错误 unresolved ID。
- [ ] RES-09 **P1** 带 styleable 的自定义 View 修改属性 → 属性值/数组索引正确，Android framework 属性可用。
- [ ] RES-10 **P1** 具有自动生成 layout-v22 的布局修改默认 XML，再加显式 v22 override → 新布局生效，显式 override 不被自动产物覆盖。
- [ ] RES-11 **P1 / split** base + dynamic feature 分别修改资源 → 资源写入所属 APK，跨 base 引用正确。
- [ ] RES-12 **P1 / ResGuard** 混淆基线修改名称/值引用/属性相关资源 → 保留 ID 映射，设备正常取值。
- [ ] RES-13 **P1** 修改 assets 并在 App 内重新读取 → 获取新内容；资源 full swap 路径按实际能力刷新/重启 Activity。
- [ ] RES-14 **P0 / macOS** Apple Silicon、Intel 可用环境分别首次/连续资源增量 → 打包二进制能启动，无架构/动态库错误。
- [ ] RES-15 **P1 / Linux x86_64** 在目标旧 glibc 环境运行已打包 AAPT2，再跑资源增量 → 无 GLIBC 符号错误，产物可在设备使用。
- [ ] RES-16 **P1 / Windows x64** 在实际支持的旧 Windows 环境运行已打包 AAPT2，再跑资源增量 → 无 API 导入/加载错误，输出路径正确。
- [ ] RES-17 **P1 / 打包** 从干净含 LFS 文件的 checkout 构建发行包并安装 → AAPT2 是真实可执行文件；未拉取 LFS 的构建不能发布 pointer 文本冒充二进制。

旧主机的二进制约束检查与完整 Jugg 所需 JDK/Python/OS 条件分开记录。现有修复记录只有 Linux/Windows 静态兼容检查，原生运行需本轮补证据。style 删除/合并的未提交调查不属于本清单已修复承诺。

## 12. Kotlin、DataBinding、androidTest 回归 [D/R]

- [ ] LANG-01 **P1** 多模块 Kotlin 各自配置 compiler plugin options，修改目标模块 → 使用该编译模块的选项，不因依赖选项累加冲突而失败。
- [ ] LANG-02 **P1 / KMP** synthetic/common 模块无自身 plugin options 时修改 common/Android 代码 → 按最近有效配置编译，设备结果正确。
- [ ] LANG-03 **P1** flavor 覆盖 minSdk 且启用 desugaring，修改包含相关 API 的代码 → 使用当前 variant 的实际 minSdk，旧设备不出现新增缺类/方法崩溃。
- [ ] LANG-04 **P1 / DataBinding** IDE 建基线后 standalone 修改表达式/BindingAdapter，再交回 IDE → 生成绑定代码可用，布局正常渲染。
- [ ] LANG-05 **P1 / Compose/KMP resources** 修改 Compose 函数、已有资源值、新资源 key → 编译并呈现新内容；必要重启后无旧资源缓存。
- [ ] LANG-06 **P1 / release** minify 基线下改代码和资源 → 启动/反射及核心页面不出现新增映射问题。
- [ ] TEST-01 **P1 / IDEA** 启用 Android Test 并完整构建，运行指定测试类/方法 → 测试真实执行、结果归属正确。
- [ ] TEST-02 **P1 / IDEA** androidTest 引用项目依赖模块，修改测试源码后重跑 → 不因依赖 classpath 缺失编译失败。
- [ ] TEST-03 **P1 / IDEA library** 运行 library androidTest 并触发 Test APK 补建 → 原 app APK 保留，之后普通 app Run 仍正确。
- [ ] TEST-04 **P1 / IDEA** 不同 library/variant 执行测试，重启再运行 → 历史对应正确目标，无串用旧 Test APK。
- [ ] TEST-05 **P1** 缺 AndroidTest 完整基线调用 instrument，以及 standalone 调用 instrument → 前者提示基线/参数问题，后者提示不支持。
- [ ] TEST-06 **P1 / IDEA** 测试失败、崩溃、取消各一轮 → 测试树和 CLI 终态反映真实结果，后续普通部署可继续。

## 13. 大 SO 发布失败修复与部署方式 [D/R]

- [ ] SO-01 **P1** 启用 native 热更新，确认后完成要求的重装，再改 `System.loadLibrary` 加载的函数 → 新结果生效。
- [ ] SO-02 **P1** 小 SO、大文件传输路径的 SO 各改一轮 → 对应传输完成后实际加载新实现，日志可辨识路径。
- [ ] SO-03 **P1** 同轮部署多个大 SO → 全部发布成功才报告成功；重启 App 后全部版本一致。
- [ ] SO-04 **P2** 在隔离设备制造目标目录/mv 发布失败 → Run 明确失败，不输出成功标记/不提交成功历史；回滚后排除原因可重试。
- [ ] SO-05 **P2** 多 SO 中第二个发布失败 → 记录回滚结果，不能把部分发布当整体成功；下一轮不错误跳过失败文件。
- [ ] SO-06 **P2** app sandbox 不可访问且输入为大 SO → 明确失败，不把大文件错误交给不支持的 rootless 延迟导入。
- [ ] SO-07 **P1** 关闭 native 热更新再 Run → 执行要求的重装，旧 native patch 不继续残留。
- [ ] DEP-01 **P1** 正常 Apply Changes 与 Quick deploy 开关分别完成代码/资源两轮 → 设备行为正确，模式切换后历史可继续使用。
- [ ] DEP-02 **P1 / 兼容设备** Android 8–10 或受限 run-as 设备走已有兼容路径 → 可安装/部署；真正失败如实反馈。
- [ ] DEP-03 **P2 / rootless** 无 sandbox 的兼容导入场景 → App 确认导入成功才推进状态；超时/失败不假成功。
- [ ] DEP-04 **P1 / Flutter** 触发外部构建并更新 Flutter 资源 → 启动读取新产物，JIT 缓存不遮蔽变更。
- [ ] DEP-05 **P1 / 自定义安装签名** 安装/签名脚本成功与失败各一轮 → 成功 APK 可运行；失败保持原始错误，修复后可恢复。

## 14. 远程构建与自定义编译器 [D/R]

- [ ] REM-01 **P0 / remote 用户** IDE 选择 SSH profile 后关闭 IDE，standalone `gradle-build` → 用所选远程环境构建，回传 APK 并在本地设备启动。
- [ ] REM-02 **P1 / iFT** 已认证 iFT profile 同流程 → 同步、远程构建、回传与部署完整成功。
- [ ] REM-03 **P1** remote profile 下改普通源码执行增量 → 正常增量仍在本机，必要全量才进入远程策略。
- [ ] REM-04 **P1** SSH 凭据缺失/过期、iFT 未认证 → standalone 明确终止并提示预配置，不等待隐藏弹窗。
- [ ] REM-05 **P1** 远程编译错误、同步失败、取消各一轮 → 非成功终态，随后修复后再次运行成功，无连接/锁泄漏。
- [ ] REM-06 **P1** 远程环境含测试密码/非白名单变量 → 命令执行有效，持久日志不回显敏感值。
- [ ] REM-07 **P2** PTY 禁用 echo 失败 → 发送敏感命令前终止，不把密码回显到日志。
- [ ] REM-08 **P1** 远程生成源码、classpath/依赖发生变化后连续两次增量 → 回传/恢复使用新内容，不使用旧 classpath 反复修复。
- [ ] REM-09 **P1 / Windows** 本地 Windows 远程同步含 CRLF gradlew/缺 wrapper 启动文件 → 支持的预检修复后构建可用，真正工具失败可定位。
- [ ] REM-10 **P1 / custom compiler** 更换自定义编译器配置后 Run，再关闭/重开 Runtime → 新编译器生效，旧加载资源释放，不因辅助下载失败取消其他有效工作。
- [ ] REM-11 **P1** 后端更新检查/事件上报不可用时进行正常增量部署 → 主流程仍完成，辅助失败有局部记录。

## 15. MCP、CLI 输出及 Agent 工作流 [D/R]

- [ ] API-01 **P0** 分别调用两 Host 的 version/tools/list → 版本与实际 Host 一致；standalone 只暴露自身已注册能力。
- [ ] API-02 **P1** standalone 调用 UI、wait-logs、instrument、clean-reinstall 等 IDEA 专属命令 → 清楚报告不支持，不错误启动不相关工程。
- [ ] API-03 **P1** 编译异步 job 从 running 到 success/failed/canceled → CLI 等待正确终态；MCP 外层 OK 不掩盖业务失败。
- [ ] API-04 **P1** 长任务期间执行默认 wait 与 `--if-compiling interrupt` → wait 等待，interrupt 交给服务端处理；最终可继续新任务。
- [ ] API-05 **P1** plain/rich/json 三模式运行长任务 → plain/rich 有进度，JSON stdout 可直接解析，stderr 不混进 JSON。
- [ ] API-06 **P1** CLI 等待时 Ctrl-C → 退出码 130、无 traceback；随后查询真实任务状态，不凭客户端退出推断服务端已取消。
- [ ] API-07 **P1 / IDEA** Host 交接/热更新后调用 layout-dump、view-locate、view-inspect、tap → 仍正确选择设备和 App，定位/点击结果可见。
- [ ] API-08 **P1 / IDEA** 部署后 wait-logs 与 activity-stack → 返回正确设备/本次 App 证据，不串到其他工程。
- [ ] AGT-01 **P1** 从 IDE 安装/更新实际使用的 Agent skill 和 hooks → 新终端命令与已安装版本一致，原用户配置保留。
- [ ] AGT-02 **P1** 无完整基线时让 Agent Hook 触发 → 按前置条件跳过，不盲目初始化/阻塞。
- [ ] AGT-03 **P1** 有基线且 Agent 修改源码后触发命令/停止 Hook → 引导现有 Jugg 验证流程；实际部署后提醒状态合理。
- [ ] AGT-04 **P1 / remote** 有远程基线时直接发起原始 Gradle 命令 → Hook 按远程策略拦截/提示；改用 Jugg 流程可完成。
- [ ] AGT-05 **P1** 升级/重装 standalone 后实际触发 Hook → Python、CLI、安装路径仍可用。

## 16. 诊断、report 与日志 [D/R]

- [ ] LOG-01 **P0** IDEA 与 standalone 各运行一次成功/失败任务 → 能找到各自本次日志，异常堆栈和工程归属正确。
- [ ] LOG-02 **P1** 清理测试工程 build 后重跑 → 全局历史日志保留，新日志可写，兼容日志入口可修复。
- [ ] LOG-03 **P1** 两个同名不同路径工程并行工作 → 日志目录分开；报告不串工程。
- [ ] LOG-04 **P1** 准备旧真实 build/jugg/log 目录，再升级运行 → 旧日志保留，冲突文件不覆盖，兼容入口可用。
- [ ] REP-01 **P0** CLI `report` 后拒绝上传 → 展示本地 ZIP、大小、目的地、条目，ZIP 留本地，无上传。
- [ ] REP-02 **P1** 在测试诊断包上明确同意上传 → 返回 report ID；服务端可获取对应内容。
- [ ] REP-03 **P1** report 带 json 模式 → 仍出现审阅与确认；EOF/Ctrl-C 保留本地包。
- [ ] REP-04 **P1** 多设备，某台读取 logcat 失败 → 只缺该台日志，其他设备与 Jugg 日志仍打包；report 的 serial 不把收集范围缩成单设备。
- [ ] REP-05 **P1** 用测试秘密构造 project info/配置 → 公共 CLI 报告中的敏感字段脱敏，正常 applicationId 等诊断字段保留。
- [ ] REP-06 **P2** prepare 后篡改 ZIP/哈希/报告 ID → upload 拒绝，不接受错误包。
- [ ] REP-07 **P1** 网络上传失败 → 错误明确，本地 ZIP 仍能用于后续诊断。
- [ ] REP-08 **P1 / IDEA** 失败后进入 ASK AI/报告对话框 → 当前任务证据可读，Sync 或后一次 Run 不替换已保存快照。

IDE 手动后端报告与 CLI 公共报告使用不同既有目标/确认流程；分别记录所选入口，不能用一条的结果替代另一条。

## 17. 热更新与资源共享 [D/R]

- [ ] UPD-01 **P1** 发布可兼容热更新，下载后重开工程 → IDEA 加载新版本；MCP version 与实际代码行为一致。
- [ ] UPD-02 **P1** 更新需要重装 → 当前活动版本维持可用，提示重装；匹配插件安装后 standalone 候选按构建标识激活。
- [ ] UPD-03 **P1** 只安装 CLI/Standalone → 不错误激活 IDEA 热更新 manifest。
- [ ] UPD-04 **P2** 更新包下载不完整/校验失败 → 活动 manifest 不被坏包替换，旧版本仍能执行。
- [ ] UPD-05 **P1** A/B 并发首次运行需释放 AAPT2/deployer 资源 → 都可运行，没有半写入文件或资源锁死锁。
- [ ] UPD-06 **P1** 旧格式可兼容 manifest、已有部署缓存升级后运行 → 可恢复部分按兼容读取，真正不可用时明确恢复，不假成功。

## 18. CI 两阶段构建回归 [D/R]

此处是 `CmdLine buildGradleBase/buildIncrementalApk`，与交互式 `jugg` CLI 分开。每个增量用例使用独立、未消费的 baseline 副本。

- [ ] CI-01 **P1 / CI 用户** 当前分支 demo 两阶段完整构建 → 基线产物完整，显式 changedFiles 增量输出 APK，安装后新逻辑生效。
- [ ] CI-02 **P1** 同一 baseline 副本增量一次后再用 → 明确拒绝已 dirty 的副本；换干净副本成功。
- [ ] CI-03 **P1** changedFiles 含不存在文件、越界文件、build 文件/过滤后无效文件 → 明确失败，不静默忽略后输出成功 APK。
- [ ] CI-04 **P1** 低文件/模块阈值，确认偏好 true/false 各用独立基线 → CI 按自身策略继续合法增量、完成 DEX 合并，持久化阈值不被改写。
- [ ] CI-05 **P1** 同低阈值矩阵输入真实语法错误 → 正常失败，没有可误用的新 APK，不因“继续增量”掩盖错误。

## 19. 发布与文档入口抽查 [D/R]

- [ ] PUB-01 **P1 / 发布** 从待发布插件 ZIP 安装后跑 INS-01、FLOW-02、RES-01 → 验证的是包内代码/二进制，而非仅本地 classes。
- [ ] PUB-02 **P1 / 发布** 最老支持 IDE、主力 IDE、新适配边界 IDE 各执行打开/Run/增量/Debug → 无启动 linkage、安装 session 或 attach 回归。
- [ ] PUB-03 **P1 / 发布** 发行包许可证/NOTICE/第三方资源检查通过 → 包内清单、Standalone deployer 元数据与实际资源一致。
- [ ] PUB-04 **P1** 中英文 Wiki CLI/Settings/目录迁移入口与本构建 help 逐项抽查 → 命令、能力限制、说明链接可用。
- [ ] PUB-05 **P1 / canary** 指定分支构建 canary 并安装 → 实际构建来源、版本和功能属于指定分支。

## 20. main 独有能力：合入前单独确认

`main` 的 `836aa0a6d` 添加 CI 基线编排命令；`95b7b5bd4` 添加复用脚本与 fallback demo。当前分支缺少 `FindNearestBaseCommitCommand`、`GetChangedFilesCommand` 和 `cmd_line/src/demo/scripts/`、`demo_flow/` 的对应内容。

- [ ] GAP-01 **P1 / 集成决策** 明确本次测试目标是否要求与最新 main 的 CI 能力对齐 → 记录“暂不包含”或待合入，不把缺失项标 PASS。
- [ ] GAP-02 **P1 / 合入后** 用 `findNearestBaseCommit`、`getChangedFiles` 和 CI scripts 跑真实基线选择/变更收集 → JSON 与实际 Git/文件变化一致。
- [ ] GAP-03 **P1 / 合入后** normal_incremental、build_file_fallback、too_many_files_fallback 三条 demo → 真实 APK 安装结果符合各流程，失败正确传递。

## 21. 建议执行顺序与结果模板

先执行所有适用 P0，再完成主力环境的 P1；接着跑跨平台/多设备/远程/特殊工程矩阵，最后做隔离环境 P2 故障注入。每个覆盖维度都至少重复“基线 → 普通增量 → 失败修复 → 再增量”，避免只验证首次启动。

| 用例 ID | Host / 平台 / 工程 / serial | PASS / FAIL / SKIP | 实际结果与证据路径 | 缺陷/补测 |
|---|---|---|---|---|
| 待填写 | | | | |

退出条件：适用 P0 全通过；适用 P1 已执行且无未解释失败；未具备环境的项有具体 SKIP 原因；main 独有能力有明确范围决策。执行结束恢复阈值、全局设置、profile、variant、测试源文件及故障注入；保留失败的原始日志。

## 22. 差异依据与覆盖映射

| 清单范围 | 主要行为 owner / 差异证据 |
|---|---|
| §3–4、§17 | `cmd_line/.../standalone/`、`standalone_bootstrap/`、CLI `jugglib.py`、`StandaloneBundleInstallService`、`JuggHotUpdateManager` |
| §5、§7 | `IdeaCliRunConfigurationManager`、`CliRunConfiguration`、`CompileContextManager`、`FileChangeManager`、`WatchServiceFileChangeMonitor`、`ExecutionLockManager` |
| §6、§10、§13 | 迁入 main 的 `JuggDeployer`、共享 `JuggDeployOrchestrator`、各 `IApplyChangesExecutor`、`NativeSandboxWriter`；`afc8d569c`、`773fa3784` |
| §8–9 | `JuggSettingDefinition`、`JuggSettingsEditor`、`JuggSettingsProjectEffects`、`JuggGlobalDirectoryMigration`；`d000e15a9`、`74d66c10e`、`c85337942` |
| §11 | `aapt2-inclink` 新预编译产物、`IncLink.cpp` / linker 变更；`aaa684b48`、`5e5d23870`、`a11445349` |
| §12 | `KotlinCompiler.selectKotlinPluginOptions`、DataBinding 运行时共享、`LibraryTestApkBuildHistory`；`c3eb5e33b`、`a56fda8f1`、`9e35deefe`、`d412de8a0` |
| §14–16 | `RemoteGradleCompileClient`、`CustomCompilerManager`、`StandaloneProjectRegistry` 能力表、report actions/bundle reader、CLI/hook 脚本 |
| §18–20 | `BuildGradleBaseCommand`、`BuildIncrementalApkCommand`、CI 两端差异和 `HEAD..main` 两个提交 |

实际阅读文档：

- `docs/ai/00_overview.md`、`99_index.md`、`98_code_map.md`、`06_testing.md`。
- `docs/ai/08_cli_tools_list.md`、`04_engineering_compat.md`、`04_engineering_ide.md`、`05_utilities.md`，以及 `02_compile_aapt2_inclink.md` §3–5。
- 历史背景：`docs/task/2026-08/standalone_cli_validation_todo.md`、`docs/task/2026-10/settings-cli-design.md`、`docs/task/2026-11/global-directory-migration.md`、`aapt2-host-compatibility-fix.md`。历史记录用于找风险和缺口，以当前实现为准。

本次文档验证：比对两种 diff、提交列表、关键实现/能力注册与上述文档；检查用例编号、路径和 Markdown 空白。不新增自动化测试；本文不改变产品事实，不需要修改维护者知识库或 Wiki。
