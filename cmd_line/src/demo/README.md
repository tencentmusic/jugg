# 文件介绍

* 1_build_base.sh：用 gradle 构建基础产物，并初始化增量构建需要的所有数据（需要根据项目情况修改）
* 2_build_incremental_apk.sh：根据变化的文件，构建增量 APK（需要根据项目情况修改）
* 3_install_and_launch.sh：安装 APK 并启动（需要根据项目情况修改）
* demo_project.zip：demo 用来构建的项目
* libs：Jugg 命令行实现