# Windows rsync 临时切换设置

用户已批准：Settings 增加 `Use Cygwin rsync on Windows (temporary)`，默认关闭使用内置 oc-rsync；开启使用本机 Cygwin，缺失时告知安装，不自动安装。从下次同步生效，非 Windows 隐藏。

改动范围：`main/.../ide/bean/JuggSettings.kt` 持久化布尔值；`idea/.../ide/ui/JuggControlPanel.kt`、`JuggControlPanelController.kt` 与 `JuggControlPanelModel` 复用设置展示/更新；`WindowsRsyncRuntime.kt` 选择运行时。内置 oc-rsync 使用系统 OpenSSH，Cygwin 使用自己的 ssh/cygpath，两个候选共用同步编排。

验证：设置持久化/展示定向回归，运行时选择、两种同步模式、真实密钥/密码认证、小型模拟产物 Flow、取消及错误退出；构建插件后检查资源。更新使用说明与 AI 知识库；完成后由只读子代理审查本批准范围。

不包含：Cygwin 自动安装、远端 Windows、真实远端 Gradle 大产物构建、HTTP 代理增强。环境变量只保留为测试/诊断覆盖入口。
