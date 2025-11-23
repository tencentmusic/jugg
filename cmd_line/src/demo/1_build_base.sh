#!/bin/sh
###############################################################################
# 演示功能：构建 Jugg 基础包，后续增量编译依赖。通过 baseBuildJuggRootDir 的输入
###############################################################################

# cd 到 demo 目录
dir=$(dirname $0)
cd $dir

# 清除产物，重新解压工程
rm -rf demo_project
rm -rf outputs
rm -rf backups
unzip -q demo_project.zip

# 环境配置
# 强烈建议 JDK 版本 >= 14，在后续构建增量 APK 的时候速度可以快几倍
echo "JAVA_HOME" $JAVA_HOME
echo "ANDROID_HOME" $ANDROID_HOME

# 运行 buildGradleBase 命令
# 功能：构建收集 Jugg 增量编译基础产物
# 参数介绍：
#     cmd：运行命令
#     baseBuildProjectDir：工程目录，相对/绝对路径都可以
#     gradleCompileTask：构建 apk 的 gradle 编译命令
#     gradleOutputApkPath：apk 输出路径（相对工程的相对路径
#     logLevel：（可选）日志级别 debug/info/warn/error，默认 debug
#     outputApkDir：（可选）apk 输出目录
# 命令 cmd_line 等价于 java -cp "lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt
../bin/cmd_line \
    cmd=buildGradleBase \
    baseBuildProjectDir=demo_project \
    gradleCompileTask=assembleDebug \
    gradleOutputApkPath=app/build/outputs/apk/debug/\*-debug.apk \
    logLevel=debug \
    outputApkDir=outputs

# 检查结果
result=$?
if [ $result == 0 ]; then
  echo "构建成功"
else
  echo "构建失败"
  exit -1
fi

# 备份 build/jugg 目录
echo "备份 build/jugg 目录"
mkdir backups
cp -r demo_project/build/jugg backups/jugg_bak
echo "备份完成"

# 打印结果
echo "Jugg 基础包备份目录：$dir/backups/jugg_bak"
echo "输出 APK："
ls "$dir/outputs/"*