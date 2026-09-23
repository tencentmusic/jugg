package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import java.io.File

/**
 * Unified manager for Jugg test modes.
 *
 * Master switch: `{juggRoot}/test_flag/enabled` (checked once, cached).
 * [juggRoot] is `~/.jugg` or the temp fallback from [JuggGlobalPathManager].
 * - When OFF: all test modes return false with zero overhead
 * - When ON: individual test modes check their flag files, and info.json is generated
 */
object TestModeManager {

    private val flagDir: File
        get() = JuggGlobalPathManager.testFlagDir()

    private data class FlagInfo(val file: String, val description: String)

    private val flags = listOf(
        FlagInfo("test_mode", "Throw exceptions instead of graceful degradation for debugging"),
        FlagInfo("runtime_test", "Test deploy compat layer at runtime"),
        FlagInfo("skip_assemble", "Skip Gradle assemble in test projects to speed up tests"),
        FlagInfo("log_verbose", "Enable verbose logging for detailed debug output"),
        FlagInfo("system_app_rootless_mode", "Force deploy by pushing material to sdcard by direct deploy and read and load by runtime on reboot"),
    )

    @Volatile
    private var masterSwitchCache: Boolean? = null

    private val isMasterEnabled: Boolean
        get() {
            if (masterSwitchCache == null) {
                masterSwitchCache = File(flagDir, "enabled").exists()
                if (masterSwitchCache == true) {
                    writeInfoJson()
                }
            }
            return masterSwitchCache!!
        }

    val isTestMode: Boolean
        get() = isMasterEnabled && File(flagDir, "test_mode").exists()

    fun isRuntimeTestEnabled(): Boolean =
        isMasterEnabled && File(flagDir, "runtime_test").exists()

    fun isSkipTestAssemblyEnabled(): Boolean =
        isMasterEnabled && File(flagDir, "skip_assemble").exists()

    val isLogVerboseEnabled: Boolean by lazy {
        isMasterEnabled && File(flagDir, "log_verbose").exists() // frequently called, use lazy
    }

    fun isForceSystemAppRootlessMode(): Boolean =
        isMasterEnabled && File(flagDir, "system_app_rootless_mode").exists()

    private fun writeInfoJson() {
        flagDir.mkdirs()
        val json = flags.joinToString(",\n  ", "{\n  ", "\n}") { flag ->
            """"${flag.file}": "${flag.description}""""
        }
        File(flagDir, "info.json").writeText(json)
    }
}
