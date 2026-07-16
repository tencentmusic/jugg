package com.sickworm.intellij.jugg.deploy.run

data class SuggestRunConfiguration(
    val moduleName: String,
    val compileCommand: String,
    val outputApkPath: String,
    val runConfigName: String = "$RUN_CONFIG_PREFIX$moduleName",
) {

    companion object {

        private const val RUN_CONFIG_PREFIX = "jugg:"

        fun getModuleNameByRunConfigName(runConfigName: String): String {
            return runConfigName.substringAfter(RUN_CONFIG_PREFIX)
        }

        fun isDefaultRunConfigName(runConfigName: String): Boolean {
            return runConfigName == DEFAULT.runConfigName || runConfigName.startsWith("Unnamed")
        }

        val DEFAULT: SuggestRunConfiguration
            get() = SuggestRunConfiguration(
                moduleName = "app",
                compileCommand = "./gradlew :app:assembleDebug",
                outputApkPath = "app/build/outputs/apk/debug/*.apk",
                runConfigName = "jugg:default"
            )
    }
}
