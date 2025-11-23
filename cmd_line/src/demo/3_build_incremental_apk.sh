#!/bin/sh
###############################################################################
# 演示功能：增量编译，输出增量 APK
###############################################################################

# cd 到 demo 目录
dir=$(dirname $0)
cd $dir

# 清除产物
rm -rf outputs

# 环境配置
# 强烈建议 JDK 版本 >= 14，在后续构建增量 APK 的时候速度可以快几倍
echo "JAVA_HOME" $JAVA_HOME
echo "ANDROID_HOME" $ANDROID_HOME

# 拉出存储的 build/jugg 目录
rm -rf backups/jugg_bak_checkout
cp -r backups/jugg_bak backups/jugg_bak_checkout

# 运行 buildIncrementalApk 命令
# 功能：编译变化文件，构建增量 APK
# 限制：目前限制了工程只能编译一次，每次运行 buildIncrementalApk 都需要使用干净的 baseBuildJuggRootDir，以减少复杂度
# 参数介绍：
#     cmd：运行命令
#     baseBuildJuggRootDir：备份的 build/jugg 目录，相对/绝对路径都可以
#     sourceProjectDir：工程目录，含待编译的文件
#     logLevel：（可选）日志级别 debug/info/warn/error，默认 debug
#     outputApkDir：增量 APK 输出目录，如果是 Dynamic feature 则会有多个 APK
#     changedFiles：变化的文件，相对/绝对路径都可以，多个用:分割
# 命令 cmd_line 等价于 java -cp "lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt
../bin/cmd_line \
    cmd=buildIncrementalApk \
    baseBuildJuggRootDir=backups/jugg_bak_checkout \
    sourceProjectDir=demo_project \
    gradleCompileTask=assembleDebug \
    logLevel=debug \
    outputApkDir=outputs \
    changedFiles=demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt:demo_project/app/src/main/res/layout/activity_main.xml
# cmd_line 等价于 java -cp "lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt


# 检查结果
result=$?
if [ $result == 0 ]; then
  echo "构建成功"
else
  echo "构建失败"
  exit -1
fi

# 打印结果
echo "输出 APK："
ls "$dir/outputs/"*