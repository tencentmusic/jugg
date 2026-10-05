#!/bin/sh
###############################################################################
# Demo: install the APK and launch the app.
###############################################################################

# Change to the demo directory.
dir=$(dirname $0)
cd $dir

# Install and launch the app.
adb install outputs/app-debug.apk
result=$?
if [ $result == 0 ]; then
  echo "Install succeeded"
else
  echo "Install failed"
fi
adb shell am start -n com.example.myapplication/.MainActivity
result=$?
if [ $result == 0 ]; then
  echo "Launch succeeded"
else
  echo "Launch failed"
fi
