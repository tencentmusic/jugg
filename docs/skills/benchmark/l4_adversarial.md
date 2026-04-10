# L4 对抗用例

> 覆盖设备选择策略、参数边界错误、端到端组合流程，共 ~10 条。
> 所有用例需要设备已连接且 App 已部署。

---

## 十一、设备选择策略

**SELECT-1: 不传 serial - 自动使用 selected device**
通过 jugg-android-dev-loop 执行任何需要设备的命令（如 `jugg screenshot`），不传 `serial`，验证返回 JSON 中 `status` 为 `OK`，命令正常执行。注意：`message` 中不包含设备选择说明文案，设备选择细节不暴露在响应中。

**SELECT-2: 多设备环境 - 指定 serial**
在连接了多台设备的环境下，通过 `jugg devices` 获取非 selected 的设备 serial，执行 `jugg screenshot` 并指定该 serial，验证截图来自指定设备（可通过截图内容区分）。

---

## 十二、错误处理与边界

**ERR-1: projectDir 缺失**
对任何需要 `projectDir` 的命令（如 `jugg deploy`），不传 `projectDir`，验证返回 JSON 中 `status` 为 `ERROR`，`errorCode` 为 `INVALID_PARAMS`，`message` 中包含 "projectDir is required" 或类似提示。

**ERR-2: projectDir 非绝对路径**
通过 jugg-android-dev-loop 执行 `jugg deploy`，传入 `projectDir="relative/path"`（不以 `/` 开头），验证返回 JSON 中 `status` 为 `ERROR`，`errorCode` 为 `INVALID_PARAMS`（要求 `pattern: "^/.+"`）。

**ERR-3: 调用不存在的命令**
通过 jugg-android-dev-loop 执行 `jugg nonexistent_cmd`，验证命令返回错误，提示命令不存在。

**ERR-4: 返回结构一致性验证**
对当前支持的全部命令分别执行一次（包括正常和异常场景），验证每次返回 JSON 都严格包含 `status`、`message`、`data`（对象）、`artifacts`（数组）四个字段；失败时额外多一个 `errorCode` 字段。

---

## 十三、组合场景（端到端工作流）

**E2E-1: 完整开发迭代流程**
1. 通过 jugg-android-dev-loop 执行 `jugg list_projects` 获取有效 `projectDir`
2. 执行 `jugg devices` 确认有设备连接
3. 执行 `jugg restart` 启动应用
4. 执行 `jugg screenshot` 截取应用初始状态
5. 修改一个源码文件
6. 执行 `jugg deploy` 编译部署
7. 如果是异步，用 `jugg get_compile_status` 轮询直到完成
8. 执行 `jugg screenshot` 截取部署后状态
9. 对比前后两次截图，确认修改已生效

**E2E-2: 编译失败后 Gradle 回退流程**
1. 故意在代码中引入一个编译错误
2. 通过 jugg-android-dev-loop 执行 `jugg deploy`，预期编译失败
3. 验证返回 JSON 中 `status` 为 `ERROR`，包含编译错误信息
4. 修复代码错误
5. 执行 `jugg gradle-build` 走 Gradle 回退
6. 验证最终编译成功

**E2E-3: UI 自动化操作流程**
1. 通过 jugg-android-dev-loop 执行 `jugg restart` 启动应用
2. 执行 `jugg screenshot` 获取当前界面
3. 执行 `jugg layout-dump` 获取 UI 层级
4. 根据 `jugg layout-dump` 结果找到目标按钮坐标
5. 执行 `jugg tap` 点击该坐标
6. 执行 `jugg screenshot` 验证点击后的界面变化
7. 执行 `jugg activity-stack` 验证当前页面是否跳转

**E2E-4: 两段式录屏验证完整流程**
1. 通过 jugg-android-dev-loop 执行 `jugg record-start`（仅传 `projectDir`）并获取 `sessionId`
2. 执行 `jugg restart` 启动应用
3. 执行 `jugg tap` 点击目标坐标
4. 等待 2~3 秒后执行 `jugg record-stop`（传 `projectDir`、`sessionId`）
5. 验证返回 JSON 中 `status` 为 `OK` 且 mp4 产物存在，播放确认包含启动与点击过程
