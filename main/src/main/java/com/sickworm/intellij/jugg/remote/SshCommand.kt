package com.sickworm.intellij.jugg.remote

class SyncFileCommand(
    localProjectPath: String,
    serverProjectPath: String,
) : BaseSshCommand() {

    override val baseCommand: String = """ft sync -s $localProjectPath --get $serverProjectPath -a "-av --delete  --exclude='build/' --exclude='imagebus/log/' --exclude='imagebus/mapping/' --exclude='local.properties' --exclude='.gradle/' --exclude='.idea/'  --exclude='buildSrc/.gradle/' --exclude='*.iml' --exclude='.git/objects/'" """

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine == "Login With User:") {
            return "1"
        }
        return null
    }
}

class CompileProjectCommand(
    serverProjectPath: String,
) : BaseSshCommand() {

    override val baseCommand: String = """cd $serverProjectPath && ./gradlew :app:assembleDebug --console=plain"""
}

class FetchOutputCommand(
    localSyncPathName: String,
) : BaseSshCommand() {

    override val baseCommand: String = """\
find_apk=${'$'}(find -name "app-universal-debug.apk" -print -quit) && \
ft sync -s $localSyncPathName/ --put ${'$'}find_apk && \
touch event.log && \
ft sync -s $localSyncPathName/ --put event.log \
"""
}

