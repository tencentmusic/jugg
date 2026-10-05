#!/bin/sh
###############################################################################
# Demo: build the Jugg baseline needed for later incremental builds.
###############################################################################

# Change to the demo directory.
dir=$(dirname $0)
cd $dir

# Remove previous outputs and extract the project again.
rm -rf demo_project
rm -rf outputs
rm -rf backups
unzip -q demo_project.zip

# Environment. JDK 14 or later can make the later incremental APK build several times faster.
echo "JAVA_HOME" $JAVA_HOME
echo "ANDROID_HOME" $ANDROID_HOME

# Run buildGradleBase to collect baseline artifacts for Jugg incremental compilation.
# cmd: command to run.
# baseBuildProjectDir: project directory, relative or absolute.
# gradleCompileTask: Gradle task that builds the APK.
# gradleOutputApkPath: APK output path relative to the project.
# logLevel: optional debug/info/warn/error level; defaults to debug.
# outputApkDir: optional APK output directory.
# cmd_line is equivalent to java -cp "lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt.
../bin/cmd_line \
    cmd=buildGradleBase \
    baseBuildProjectDir=demo_project \
    gradleCompileTask=assembleDebug \
    gradleOutputApkPath=app/build/outputs/apk/debug/\*-debug.apk \
    logLevel=debug \
    outputApkDir=outputs

# Check the result.
result=$?
if [ $result == 0 ]; then
  echo "Build succeeded"
else
  echo "Build failed"
  exit -1
fi

# Back up the build/jugg directory.
echo "Backing up build/jugg directory"
mkdir backups
cp -r demo_project/build/jugg backups/jugg_bak
echo "Backup complete"

# Print the results.
echo "Jugg baseline backup directory: $dir/backups/jugg_bak"
echo "Output APK:"
ls "$dir/outputs/"*
