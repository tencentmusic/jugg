#!/bin/sh
###############################################################################
# Demo: modify project sources to verify that the incremental APK applies the changes.
# For an integrated project, this corresponds to:
# 1. Check out the target commit.
# 2. Find files changed since the baseline commit.
# 3. Determine whether incremental compilation is possible; stop if Gradle files changed.
###############################################################################

# Change to the demo directory.
dir=$(dirname $0)
cd $dir


# Extract the project again.
rm -rf demo_project
unzip -q demo_project.zip

# Add a startup toast to MainActivity.kt.
mainActivityKtFile=demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt
sed -i '' '/super\.onCreate/a\
        android.widget.Toast.makeText(this, "Hello Jugg cmd line!", android.widget.Toast.LENGTH_SHORT).show()
' $mainActivityKtFile
# Verify that the toast was inserted.
grep -q 'Hello Jugg cmd line' $mainActivityKtFile
if [ $? == 0 ]; then
  echo "Updated file: $dir/$mainActivityKtFile"
else
  echo "Failed to update file (the source may have changed)"
  exit -1
fi

mainActivityLayoutFile=demo_project/app/src/main/res/layout/activity_main.xml
perl -0777 -i -pe 's/android:text=.*/android:text="Hello World for Jugg cmd line!"/' $mainActivityLayoutFile
grep -q 'Jugg cmd line' $mainActivityLayoutFile
if [ $? == 0 ]; then
  echo "Updated file: $dir/$mainActivityLayoutFile"
else
  echo "Failed to update file (the source may have changed)"
  exit -1
fi


