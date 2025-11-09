#!/bin/sh
###############################################################################
# 演示功能：修改工程源码，最终验证增量修改是否生效
###############################################################################

# cd 到 demo 目录
dir=$(dirname $0)
cd $dir


# 重新解压工程
rm -rf demo_project
unzip -q demo_project.zip

# 给 MainActivity.kt 启动时加一个 toast
mainActivityKtFile=demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt
sed -i '' '/super\.onCreate/a\
        android.widget.Toast.makeText(this, "Hello Jugg cmd line!", android.widget.Toast.LENGTH_SHORT).show()
' $mainActivityKtFile
# 校验是否插入成功
grep -q 'Hello Jugg cmd line' $mainActivityKtFile
if [ $? == 0 ]; then
  echo "更新文件成功：$dir/$mainActivityKtFile"
else
  echo "更新文件失败（可能源代码有变化）"
  exit -1
fi

mainActivityLayoutFile=demo_project/app/src/main/res/layout/activity_main.xml
perl -0777 -i -pe 's/android:text=.*/android:text="Hello World for Jugg cmd line!"/' $mainActivityLayoutFile
grep -q 'Jugg cmd line' $mainActivityLayoutFile
if [ $? == 0 ]; then
  echo "更新文件成功：$dir/$mainActivityLayoutFile"
else
  echo "更新文件失败（可能源代码有变化）"
  exit -1
fi



