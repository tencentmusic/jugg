# CC Switch Claude Hooks 兼容方案

## 背景与结论

用户反馈：Jugg 安装的 Claude Code hooks 在 CC Switch 切换 Claude 供应商后消失。

结论：反馈成立。CC Switch `v3.18.0` 在普通切换路径会以目标供应商的配置快照重写 `~/.claude/settings.json`。Jugg 当前只向这个实时文件合并 hooks，因此 hooks 不属于目标快照或 CC Switch 的 Common Config 时，下一次切换必然丢失。

CC Switch 已提供兼容入口：每个 Claude provider 可启用 `Common Config`；切换时，启用该选项的 provider 会将全局 `common_config_claude` 深度合并到实时配置。`hooks` 是可保留字段，不会被 CC Switch 的提取器过滤。Common Config 未启用时，CC Switch 不会自动同步 Jugg 新写入的 hooks。

## 依据

- CC Switch `v3.18.0` 源码提交 `878c26f31e012ba32b9772bd080bd4fa9e7d495e`：
  - `src-tauri/src/services/provider/mod.rs#switch_normal`：切换时回填旧 provider，再写入目标 provider。
  - `src-tauri/src/services/provider/live.rs#write_live_with_common_config`：普通 Claude 切换写入实时 `settings.json`。
  - `src-tauri/src/services/provider/live.rs#build_effective_settings_with_common_config`：仅 `meta.commonConfigEnabled == true` 的 provider 合并 Common Config。
  - `src-tauri/src/services/provider/mod.rs#extract_claude_common_config`：仅剥离凭据、模型和端点等供应商字段，保留 `hooks`。
- CC Switch 用户手册 `2.2 Switch Provider`：明确 Claude 切换会修改 `~/.claude/settings.json`，且立即生效。
- Jugg `main/.../ai/skills/JuggHookInstaller.kt`：仅向 `~/.claude/settings.json` 作幂等合并，未同步到 provider 快照。

## 方案选择

采用「安装实时 hooks + Common Config 文件引导」：成功安装 Claude hooks 后，Jugg 自动检测 CC Switch 配置目录并提供 Common Config JSON 导出入口。

Jugg 不读取、写入、监听或备份 `~/.cc-switch/cc-switch.db`，也不创建、修改、删除或自动发现 CC Switch provider。CC Switch 桌面版与 `cc-switch-cli` 默认均使用 `~/.cc-switch`；Jugg 只检查该目录是否存在，并额外检查当前进程可见的 `CC_SWITCH_CONFIG_DIR`。

## 用户操作与边界

1. 在 Jugg 安装窗口选择 Claude Code 与 agent hooks。
2. Jugg 自动安装 Claude skill、CLI 与实时 hooks。
3. 每次成功完成 Claude hooks 安装后，用户关闭安装结果弹窗，Jugg 异步检测 CC Switch / `cc-switch-cli` 配置目录；未检测到时不显示额外内容。
4. 检测到后，Jugg 询问是否创建 Common Config JSON。取消时不写入任何文件。
5. 确认后，Jugg 从实时 Claude 设置中只提取 Jugg 管理的 command hooks，写入并打开 `~/.jugg/cc-switch/claude-hooks-common-config.json`。
6. 确认弹窗明确提示用户将该文件完整 JSON 粘贴到 CC Switch 的 Common Config；后续 Provider 启用与新增 Provider 配置均由用户在 CC Switch 中完成。

## Jugg 实现

### 1. 安装窗口

每次成功完成 Claude hooks 安装后，安装结果关闭才启动异步目录检测，不向 CC Switch 传递配置。

### 2. 安装结果

`CcSwitchCommonConfigGuideExporter` 只基于实时 Claude 设置导出 Jugg 自有 hooks，不包含用户已有的其他 hooks。导出文件在 IDE 中打开，供用户自行复制粘贴。

## 验证计划

先按 TDD 写失败用例，再实现生产代码。

| 文件 | 层级 | 覆盖内容 |
| --- | --- | --- |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggHookInstallerTest.kt` | L1 | 导出的 Common Config 只包含 Jugg Claude command hooks，且写入 `~/.jugg/cc-switch`。 |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/ui/InstallJuggSkillsDialogTest.kt` | L2 | 异步确认弹窗明确说明完整 JSON 的粘贴位置。 |

定向执行：

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggHookInstallerTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.ui.InstallJuggSkillsDialogTest"
```

## 验收标准

- 检测到 CC Switch 后由用户选择是否打开导出的 Common Config JSON，不产生或改动任何 provider。
- Jugg 只写入当前 Claude 实时设置及自身安装目录。
- 原安装结果弹窗关闭后才异步检测 CC Switch / CLI 配置目录。
- 用户确认后才创建并打开仅含 Jugg hooks 的 Common Config JSON。
- 未使用 CC Switch 的用户行为、配置文件和安装输出不变。
