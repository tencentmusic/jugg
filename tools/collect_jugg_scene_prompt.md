# 采集 Jugg 问题现场

把**本文件全文**发给当前 Android 工程里的 AI 助手（Cursor / Claude Code / Codex / Trae 等）并让它执行。

采集结束后会自动打开结果文件夹。把桌面上的 `jugg_scene_*.zip` 发回维护者即可，不要在对话里粘贴二进制内容。

Windows 需要本机已安装 [Git for Windows](https://git-scm.com/download/win)（用它的 bash 跑官方脚本），或可用 WSL。

---

## Agent 指令

你现在只做 Jugg 现场采集，不是修 bug、不是改代码、不是重新编译。

**禁止：**

- 修改任何源码、Gradle、配置、资源、Manifest
- 再次 Run / Build / Deploy / 安装 / 卸载 / 清数据 / Clear Jugg Build / 重启 App
- 使用 `--skip-adb` 或 `--no-open`
- 把 zip 或 APK/DEX 内容 cat 到对话里
- 把官方脚本改写成另一份实现，或内联一份本地副本顶替下载

这些操作会覆盖现场。手机保持解锁，USB 调试保持开启，采集期间不要操作 App。

沙箱拦文件、网络、adb 或 USB 设备时，申请本机完整权限后重试，不要改成跳过 adb，也不要手写采集逻辑。

### 1. 定位工程

1. 当前工作区根目录若存在 `build/jugg/`，就用它。
2. 否则在工作区内最多 4 层查找 `build/jugg` 目录；只命中一个就用其父目录作为工程根。
3. 找不到就停下来问用户工程路径，不要编造目录，不要继续采集。
4. 已知应用包名时带 `--package-name`；多设备且用户指定了设备时带 `--device-serial`。不要猜错包名；不确定就让脚本自动推断。

### 2. 下载官方脚本并执行

只使用开源仓库里的脚本，不要改内容：

- 首选：https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/collect_jugg_scene.command
- 若 404 或下载失败：https://raw.githubusercontent.com/tencentmusic/jugg/develop/tools/collect_jugg_scene.command

保存后校验文件以 `#!/usr/bin/env bash` 开头，且包含 `collect_jugg_scene`。下载失败就停止，告诉用户需要能访问 GitHub raw。

下载示例：

```bash
curl.exe -fsSL "https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/collect_jugg_scene.command" -o "$TEMP/collect_jugg_scene.command"
```

macOS / Linux 把 `curl.exe` 换成 `curl`，输出路径换成 `/tmp/collect_jugg_scene.command`。Windows 路径交给 Git Bash 时转成 `/c/Users/...` 这种 Unix 路径。

输出目录优先用桌面：

- macOS / Linux：`$HOME/Desktop`，没有桌面再用 `$HOME`
- Windows：`[Environment]::GetFolderPath('Desktop')`，Git Bash 下也可用 `$HOME/Desktop` 或 `$HOME/OneDrive/Desktop`

执行（把工程路径和桌面路径换成实际值）：

```bash
bash "<SCRIPT_PATH>" "<ANDROID_PROJECT_ROOT>" --output-root "<DESKTOP_DIR>" --zip
```

如果脚本报 `unknown argument: --zip`，说明远端还是旧版，去掉 `--zip` 再跑一次，然后把生成的 `jugg_scene_*` 目录打成同名 zip。

Windows 用 Git Bash 跑同一条命令，例如：

```powershell
& "$env:ProgramFiles\Git\bin\bash.exe" -lc "bash '<SCRIPT_PATH>' '<ANDROID_PROJECT_ROOT>' --output-root '<DESKTOP_DIR>' --zip"
```

Git Bash 找不到时再试：

1. `%LOCALAPPDATA%\Programs\Git\bin\bash.exe`
2. `wsl.exe bash "<SCRIPT_PATH>" ... --zip`

没有 bash / WSL 就停止，请用户安装 Git for Windows。不要用 PowerShell 重写采集脚本。

脚本结束后如果文件管理器没有打开，再补一次：

- macOS：`open -R "<ZIP_PATH>"`，没有 zip 则 `open "<OUT_DIR>"`
- Windows：`explorer.exe /select,"<NATIVE_ZIP_OR_DIR>"`，不行就打开目录
- Linux：`xdg-open "<OUT_DIR>"`

### 3. 完成后告诉用户

读输出目录里的 `summary.txt`、`manifest.txt`、`meta/adb_resolution.txt`、`meta/adb_targets.txt`。回复时只说明：

- 工程路径
- `jugg_scene_*` 目录路径
- zip 路径（若生成成功）
- adb 是否找到、采集了几台设备、是否拉到了设备 APK / overlay
- 请用户把文件管理器中打开的 zip（或整个 `jugg_scene_*` 目录）发给维护者

本地文件缺失时如实说缺了什么，仍然把已采集到的 zip/目录交给用户。不要尝试补跑 Jugg 来生成现场。
