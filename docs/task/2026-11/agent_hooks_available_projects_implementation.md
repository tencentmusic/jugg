# Agent hooks available-project discovery implementation

Purpose / Decision / Impact: Make agent hooks associate edits with multiple Jugg projects inside a session workspace. Use `complete_flag` as the project marker, prune descendants when the workspace root is a Jugg project, otherwise scan two visible directory levels, and query Stop status per matched project.

## 目的

修复 Agent 从多工程仓库根目录运行时，Hook 将仓库根目录误当成 Android 工程的问题。

## 决策

- Session state 使用 `projectCwds` 和完整路径 `sessionWriteFiles`，删除 `sessionWriteFileNames`。
- `build/jugg/database/compile_context.db/complete_flag` 是项目发现标记，不再判断 `settings.gradle`。
- workspace root 自身存在 `complete_flag` 时停止向下搜索。
- workspace root 不存在 `complete_flag` 时，只扫描两层非隐藏子目录。
- Edit 按 available project 的最长路径匹配文件；无法匹配的文件进入 `unmatchedFiles`。
- Stop 按 `projectCwds` 独立查询 status，并聚合阻塞结果。

## 影响

当前 Hook 源文件和本机已安装 Hook 已同步。CLI 和 skill 版本递增，以便后续安装包刷新脚本。

## 验证

- 根目录与嵌套项目同时存在 `complete_flag` 时，只返回根目录。
- 非 available 根目录最多发现两层可见子目录。
- 隐藏目录被过滤。
- 文件匹配使用最长项目路径。
- Python 语法检查和新增 hook 项目发现测试通过。
