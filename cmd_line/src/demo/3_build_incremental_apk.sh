#!/bin/sh
###############################################################################
# Demo: compile changes and produce an incremental APK.
###############################################################################

# Change to the demo directory.
dir=$(dirname $0)
cd $dir

# Remove previous outputs.
rm -rf outputs

# Environment. JDK 14 or later can make the incremental APK build several times faster.
echo "JAVA_HOME" $JAVA_HOME
echo "ANDROID_HOME" $ANDROID_HOME

# Copy the saved build/jugg directory.
rm -rf backups/jugg_bak_checkout
cp -r backups/jugg_bak backups/jugg_bak_checkout

# Run buildIncrementalApk to compile changed files into an incremental APK.
# Limitation: each project can currently be compiled only once per baseline. Use a clean
# baseBuildJuggRootDir for each buildIncrementalApk run to reduce complexity.
# cmd: command to run.
# baseBuildJuggRootDir: backed-up build/jugg directory, relative or absolute.
# sourceProjectDir: project directory containing the changed files.
# customCompilerJars: custom compilers; see custom_compiler/README.md.
# logLevel: optional debug/info/warn/error level; defaults to debug.
# outputApkDir: incremental APK output directory; Dynamic Features may produce multiple APKs.
# changedFiles: changed file paths, relative or absolute, separated by colons.
# cmd_line is equivalent to java -cp "lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt.
../bin/cmd_line \
    cmd=buildIncrementalApk \
    baseBuildJuggRootDir=backups/jugg_bak_checkout \
    sourceProjectDir=demo_project \
    customCompilerJars=custom_compilers/custom_compiler_instrument-1.0.jar:custom_compilers/dependency.jar \
    logLevel=debug \
    outputApkDir=outputs \
    changedFiles=demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt:demo_project/app/src/main/res/layout/activity_main.xml
# cmd_line is equivalent to java -cp "lib/*" com.sickworm.intellij.jugg.cmdline.CmdLineKt.


# Check the result.
result=$?
if [ $result == 0 ]; then
  echo "Build succeeded"
else
  echo "Build failed"
  exit -1
fi

# Print the result.
echo "Output APK:"
ls "$dir/outputs/"*
