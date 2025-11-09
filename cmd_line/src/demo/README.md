# 文件介绍

* bin：调用封装
* libs：Jugg 命令行实现
* demo：演示 demo
  * 1_build_base.sh：用 gradle 构建基础产物，并初始化增量构建需要的所有数据（需要根据项目情况修改）
  * 2_modify_project.sh：修改工程源码，最终验证增量 APK 修改是否生效（需要根据项目情况修改）
  * 3_build_incremental_apk.sh：根据变化的文件，构建增量 APK（需要根据项目情况修改）
  * 4_install_and_launch.sh：安装 APK 并启动（需要根据项目情况修改）