# Agent 使用 Jugg CLI 真机调试复盘

**Purpose** — Review a Jojo on-device debugging session through the Jugg CLI.
**Decision** — Improve coordinate discoverability first. Reproduce the file-state discrepancy before modifying recovery. Evaluate per-action log windows for repeated interactions.
**Impact** — Fewer unintended taps and redundant compilations, with clearer evidence for each verification step.

## 现场证据

- 2026-11-23，Jojo Android、Samsung Note10+、IDEA Runtime 4.0.6-SNAPSHOT。三轮均成功增量部署，文件数分别为 7、1、1。第一轮从服务端收到 deploy 到结果约 10 秒，第二轮约 8 秒。技能记录使用了 Gemini 安装目录中的 CLI 脚本，历史 CLI 版本未采集。
- 同一时间段的 `compile_2026-11-23_16-50-36.0.log` 第 7325–7410 行显示，Git 刷新三次均返回 8 个 CRC 候选、6 个匹配历史、2 个待处理。五个 Kotlin 文件都已进入 Git 候选。17:58:42 的 `touch` 先经文件监听加入 5 个文件，随后 Git 刷新的 CRC 统计照旧，`status` 列出 7 个。本次无法判定历史 CRC 代表先前部署的正确内容，还是与目标设备实际部署状态有偏差。
- 现场日志第 8811、8828–8832 行确认 `contentDesc` 零匹配，以及最终像素坐标 `1277,2793` 的点击。实践记录中的设备日志确认 `NextScene` 与界面从「金」切到「木」。实践记录还记载了把 dp 中心直接传给坐标参数的误点，该次请求未在对应 MCP 片段找到。
- 播放按钮由 Jojo 的渲染条件隐藏。解除条件后，起播进入歌单加载。补充业务日志后确认 `source=BACKEND`、WNS Business `code=3`，可按 trace ID 查服务端原因，实际播放未成功。Jugg 侧三轮部署均完成。

## 优化顺序

1. **先解决坐标单位的发现成本。** `LayoutDumpHelper` 在 density 有效时输出整数 dp，`TapMcpToolAction` 将绝对坐标直接交给设备输入。仓库 CLI 手册已写明 px，`jugg tap --help` 的起点和终点参数缺单位。先在 CLI help、MCP 参数描述和 skill 示例明确 `x/y`、`end-x/end-y` 的 px 约定，提示先尝试当前快照中的唯一选择器，再从 `units.bounds` 判断是否需要结合 `deviceInfo.density` 换算。若手算仍频繁出现，评估从原始屏幕 px 边界输出可直接传给 tap 的中心点，先验证高密度设备和窗口坐标一致性。复现选择器问题时，比对同一个稳定页面的完整快照和 `view-locate`；现场只证实零匹配。当前实现把普通零匹配编码成 `INTERNAL_ERROR`，可评估在调用方兼容检查后改为现有 `ELEMENT_NOT_FOUND`。
2. **优先复现文件状态差异，再决定修复层。** 现有 `status` 给出未编译队列，Git 恢复根据 `DeployHistoryDb` 保存的 CRC 对照候选，IDEA 的状态入口调用 `updateChangedFiles()`。在有既有基线的测试工程，保存工作区文件的实际 CRC、历史 CRC 和设备部署记录，在同一基线上对照两种场景：内容改动且文件事件丢失，以及只改时间戳。前者应进入待编译队列，后者应避免因 Agent 的误判触发重复编译。若实际内容与历史不同但缓存返回旧 CRC，修恢复计算。若工作区等于历史但设备仍旧，查部署历史与设备对应关系。若这些文件已部署，修 skill 指引，解释 Git 工作区差异与 Jugg 待编译队列的关系，移除把 `touch` 当成常规补救的用法。后续可让 status 标识本次是完成 Git 对照还是读取忙碌快照；这两种结果在当前实现都可返回 OK。
3. **按交互验证日志。** 现有 `wait-logs` 从最近部署或重启开始，适合部署后首轮操作。连续点击可命中同一部署窗口中的旧 marker，短期在 skill 中指导使用本轮唯一 marker 并复查新布局。若多轮复用同一 marker 的场景常见，给现有 `wait-logs` 增加显式设备时间下界，验收用例为两次触发同一 marker 时，第二次等待跳过第一条并返回第二条。现场未观察到 `wait-logs` 误命中。

本次使用现场日志和静态实现验证，没有重放操作或修改 CLI。坐标帮助可用实际 CLI help 和真机点击验证；文件恢复若确认回归，在既有 `GitFileChangesDetectorTest` 或 `DeployHistoryDbCrcCacheTest` 选择最接近观察结果的 owner，并用原路径真机验证。日志窗口变化用两次交互验证，确定性过滤规则可由现有动作测试覆盖。

## 已确认的点击单位方案

CLI 的 `--unit dp|px` 和 MCP 的 `unit` 在绝对坐标模式下必填。点击、长按和滑动共用规则，滑动的两个端点共用单位。百分比和元素模式继续使用现有入口。布局转坐标操作时读取 `units.bounds`，旧的像素坐标调用显式补上 `px`。

CLI 在连接 Runtime 前校验，MCP 在设备操作前校验。绝对 dp 坐标在目标设备查询当前 density 后换算，优先采用设备 override density。缺少有效 density 时返回错误。手势结果中的实际坐标仍以 px 表示。范围包括手势动作和 schema、CLI 解析和 help、既有手势回归测试、skill/CLI 版本和英文知识库及 Wiki 双语说明。

验收中，旧实现接受缺少单位的坐标，新增的 CLI 和 MCP 回归用例均先失败。修改后，35 个手势测试、3 个 MCP 校验测试、24 个 MCP 成功路径测试和 98 个 CLI 测试通过。Wiki 镜像检查和生产构建通过。扩大回归时，`testRestartAppRejectSerialArgument` 仍按旧契约拒绝 `serial`，与现有 restart schema 不一致。本次未在真机上重放手势。
