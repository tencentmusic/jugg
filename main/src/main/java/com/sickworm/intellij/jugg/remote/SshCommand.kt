package com.sickworm.intellij.jugg.remote

import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo

abstract class IftSyncCommand : BaseSshCommand() {

    /**
     * Store the real result of command due to iFt won't give correct exit code.
     * Command will listen to the output. e.g.:
     * when run success -> will get "task done"
     * when run failed -> will get something like: "set device failed: no device online"
     */
    private var iftResult: Int = ERROR_NO_RESULT

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine.contains("task done")) {
            if (iftResult == ERROR_NO_RESULT) {
                iftResult = SUCCESS
            }
        }
        if (terminalOutputLine.contains("run rsync failed:") || terminalOutputLine.contains("set device failed:")) {
            iftResult = ERROR_FAILED
        }
        if (terminalOutputLine == "Login With User:") {
            return "1"
        }
        return null
    }

    override fun hasFinishWithResult(terminalOutputLine: String): Int? {
        if (super.hasFinishWithResult(terminalOutputLine) != null) {
            // reach end, return correct exit code
            return iftResult
        }
        if (terminalOutputLine.contains("rsync error:")) {
            // canceled by user, in this case super.hasFinishWithResult will never be non-null (no idea why)
            iftResult = ERROR_CANCELED
            return iftResult
        }
        return null
    }

    companion object {
        private const val SUCCESS = 0
        private const val ERROR_NO_RESULT = -1000
        private const val ERROR_FAILED = -1001
        private const val ERROR_CANCELED = -1002
    }
}

class SyncFileCommand(
    localProjectPath: String,
    remoteProjectPath: String,
) : IftSyncCommand() {

    override val baseCommand: String = """ft sync -s $localProjectPath --get $remoteProjectPath -a "-av --delete  --exclude='build/' --exclude='imagebus/log/' --exclude='imagebus/mapping/' --exclude='local.properties' --exclude='.gradle/' --exclude='.idea/'  --exclude='buildSrc/.gradle/' --exclude='*.iml' --exclude='.git/objects/'" """

}

class CompileProjectCommand(
    serverProjectPath: String,
) : BaseSshCommand() {

    override val baseCommand: String = """cd $serverProjectPath && ./gradlew :app:assembleDebug --console=plain"""
}

class FetchOutputCommand(
    outputApkName: String,
    remoteToLocalIftConfigName: String,
) : IftSyncCommand() {

    override val baseCommand: String = """\
find_apk=${'$'}(find -name "$outputApkName" -print -quit) && \
ft sync -s $remoteToLocalIftConfigName/ --put ${'$'}find_apk && \
touch event.log && \
ft sync -s $remoteToLocalIftConfigName/ --put event.log \
"""

}

class FetchClasspathCommand(
    remoteProjectPath: String,
    remoteToLocalClasspathPath: String,
    modules: List<ModuleBuildPathInfo>,
) : IftSyncCommand() {

    private val remoteProjectClasspathDir = "build/jugg/sync/classpath"

    private val outputDirList = modules.flatMap {
        it.allClassPathRelative
    }.joinToString(" ")

    override val baseCommand: String = """\
cd $remoteProjectPath ; \
rm -rf $remoteProjectClasspathDir ; \
mkdir -p $remoteProjectClasspathDir ; \
source_dirs=("app" "module_party") ; \
sub_dirs=("build/intermediates/javac/debug/classes" "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes" "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar" "build/tmp/kotlin-classes/debug"); \
for dir in "${'$'}{source_dirs[@]}" ; do \
  for sub_dir in "${'$'}{sub_dirs[@]}" ; do \
    full_sub_dir="${'$'}dir/${'$'}sub_dir" ; \
    [ -e "${'$'}full_sub_dir" ] && \
    mkdir -p `dirname "$remoteProjectClasspathDir/${'$'}full_sub_dir"` && \
    cp -rf "${'$'}full_sub_dir" "$remoteProjectClasspathDir/${'$'}full_sub_dir" ; \
  done ; \
done ; \
ft sync -s $remoteToLocalClasspathPath --put $remoteProjectClasspathDir \
"""

}

