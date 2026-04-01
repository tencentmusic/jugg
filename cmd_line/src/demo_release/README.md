# `demo_release` 使用说明

这套 demo 用于演示 **release + minify + AabResGuard** 场景下的 Jugg 命令行增量构建流程。

## 文件说明

- `1_build_base.sh`：执行 `bundleReleaseToApk`，产出 release 基线 APK，并初始化后续增量构建依赖的 `build/jugg`
- `2_modify_project.sh`：在工作副本中修改源码与资源，用于验证 release 增量 APK 是否生效
- `3_build_incremental_apk.sh`：基于备份的 `build/jugg` 和变更文件生成 release 增量 APK
- `4_install_and_launch.sh`：安装生成的 release APK 并启动应用
- `_common.sh`：公共路径与准备逻辑，兼容源码仓库与 distribution 解压目录两种运行方式

## 前置条件

- 已配置 `JAVA_HOME`
- 已配置 `ANDROID_HOME`
- 本机可执行 `adb`
- 本机可执行 `unzip`
- release 工程侧已提供 `bundleReleaseToApk` 任务（当前 demo 对应 `android_demo_project/app/aabResGuard.gradle`）

## 运行步骤

按顺序执行：

```bash
sh 1_build_base.sh
sh 2_modify_project.sh
sh 3_build_incremental_apk.sh
sh 4_install_and_launch.sh
```

## 关键产物

- 基线 / 增量 APK：`outputs/duplicated-app.apk`
- Jugg 基线目录备份：`backups/jugg_bak`
- release mapping：`demo_project/app/build/outputs/mapping/release/mapping.txt`
- release usage：`demo_project/app/build/outputs/mapping/release/usage.txt`

## 工程准备逻辑

- 在 distribution 解压目录下运行时，优先使用同目录中的 `demo_project.zip`
- 在源码仓库中运行时，若没有 `demo_project.zip`，则回退复制仓库内的 `android_demo_project`
- 自定义编译器 jar 默认复用 `../demo/custom_compilers`
