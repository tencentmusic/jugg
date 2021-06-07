package com.sickworm.intellij.aidp

import java.io.File

class Dexer {

    fun dex() {
        Runtime.getRuntime().exec("""jar cvf src\test\build\out.jar -C src\test\build .""").waitFor()
        Runtime.getRuntime().exec("""D:\Android\sdk\build-tools\30.0.3\dx.bat --dex --output=src\test\build\out.dex src\test\build\out.jar""").waitFor()
        File("""src\test\build\out.jar""").delete()
        // dx --min-sdk-version
    }
}