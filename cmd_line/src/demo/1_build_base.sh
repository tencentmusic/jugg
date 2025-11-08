#!/bin/sh

# cd 到 demo 目录
dir=$(dirname $0)
cd $dir

# 重新解压工程
rm -rf demo_project
unzip -q demo_project.zip -x "__MACOSX/*" "*.DS_Store"

# 环境配置
# 强烈建议 JDK 版本 >= 14，在后续构建增量 APK 的时候速度可以快几倍
echo "JAVA_HOME" $JAVA_HOME
echo "ANDROID_HOME" $ANDROID_HOME

# 运行 buildGradleBase 命令
# 功能：构建收集 Jugg 增量编译基础产物
# 参数介绍：
# cmd：运行命令
# baseBuildProjectDir：工程目录，相对/绝对路径都可以
# gradleCompileTask：构建 apk 的 gradle 编译命令
# outputApkPath：apk 输出路径（相对工程的相对路径
java -cp "../lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt \
    cmd=buildGradleBase \
    baseBuildProjectDir=demo_project \
    gradleCompileTask=assembleDebug \
    outputApkPath=app/build/outputs/apk/debug/\*-debug.apk

# 检查结果
result=$?
if [ $result == 0 ]; then
  echo "构建成功"
else
  echo "构建失败"
fi

