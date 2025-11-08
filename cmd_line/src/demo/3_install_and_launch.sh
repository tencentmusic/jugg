#!/bin/sh

# cd 到 demo 目录
dir=$(dirname $0)
cd $dir

# 安装应用并启动
adb install outputs/app-debug.apk
result=$?
if [ $result == 0 ]; then
  echo "安装成功"
else
  echo "安装失败"
fi
adb shell am start -n com.example.myapplication/.MainActivity
result=$?
if [ $result == 0 ]; then
  echo "启动成功"
else
  echo "启动失败"
fi