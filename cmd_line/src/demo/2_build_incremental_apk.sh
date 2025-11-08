#!/bin/sh

# cd 到 demo 目录
dir=$(dirname $0)
cd $dir

# 环境配置
# 强烈建议 JDK 版本 >= 14，在后续构建增量 APK 的时候速度可以快几倍
echo "JAVA_HOME" $JAVA_HOME
echo "ANDROID_HOME" $ANDROID_HOME
rm -rf outputs

# 运行 buildIncrementalApk 命令
# 功能：编译变化文件，构建增量 APK
# 限制：目前限制了工程只能编译一次，每次运行 buildIncrementalApk 都需要使用干净的 baseBuildProjectDir，以减少复杂度
# 参数介绍：
# cmd：运行命令
# baseBuildProjectDir：工程目录，相对/绝对路径都可以
# outputApkDir：增量 APK 输出目录，如果是 Dynamic feature 则会有多个 APK
# changedFiles：变化的文件，相对/绝对路径都可以，多个用:分割
java -cp "../lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt \
    cmd=buildIncrementalApk \
    baseBuildProjectDir=demo_project \
    outputApkDir=outputs \
    gradleCompileTask=assembleDebug \
    changedFiles=demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt:demo_project/app/src/main/java/com/example/myapplication/MainActivity2.java

# 检查结果
result=$?
if [ $result == 0 ]; then
  echo "构建成功"
else
  echo "构建失败"
fi

