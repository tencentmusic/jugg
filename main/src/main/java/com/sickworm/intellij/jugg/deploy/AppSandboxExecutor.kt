package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance

/**
 * Resolves and executes app-private filesystem commands using one fixed permission mode.
 */
class AppSandboxExecutor(
    private val adb: IDeviceAdb,
    private val packageName: String,
    loggerArg: Logger,
) {

    enum class Mode {
        RUN_AS,
        DIRECT_SHELL,
        ROOT_DIRECT,
        SU_ROOT,
        UNAVAILABLE,
    }

    enum class ApplyChangesCapability {
        COMPATIBLE,
        INCOMPATIBLE,
    }

    private enum class SuStyle {
        UID,
        DEFAULT,
        COMMAND,
    }

    private data class Resolution(
        val mode: Mode,
        val dataDir: String? = null,
        val suStyle: SuStyle? = null,
        val detail: String,
    )

    private data class RootProbeResult(
        val resolution: Resolution? = null,
        val requested: Boolean = false,
        val reconnected: Boolean = false,
        val output: String = "not attempted",
    )

    private val logger = loggerArg.getInstance("AppSandboxExecutor")
    private val runAsUid: Int? by lazy {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "Unsafe package name: $packageName" }
        probeCompatibleRunAsUid(adb, packageName)
    }
    private val resolution: Resolution by lazy { resolve() }

    val mode: Mode
        get() = resolution.mode

    val applyChangesCapability: ApplyChangesCapability
        get() {
            val uid = runAsUid
            return if (uid != null && uid in AS_DEPLOYER_UID_RANGE) {
                ApplyChangesCapability.COMPATIBLE
            } else {
                ApplyChangesCapability.INCOMPATIBLE
            }
        }

    val dataDir: String?
        get() = resolution.dataDir

    val unavailableReason: String?
        get() = resolution.detail.takeIf { resolution.mode == Mode.UNAVAILABLE }

    fun exec(command: String, repairCodeCache: Boolean = false): String {
        return execInternal(command, repairCodeCache, noFallback = false)
    }

    fun execNoFallback(command: String, repairCodeCache: Boolean = false): String {
        return execInternal(command, repairCodeCache, noFallback = true)
    }

    fun absolutePath(relativePath: String): String? {
        require(relativePath.isEmpty() || isSafeRelativePath(relativePath)) {
            "Unsafe app-relative path: $relativePath"
        }
        val suffix = if (relativePath.isEmpty()) "" else "/$relativePath"
        return when (mode) {
            Mode.DIRECT_SHELL, Mode.ROOT_DIRECT, Mode.SU_ROOT -> dataDir?.let { "$it$suffix" }
            Mode.RUN_AS -> "/data/data/$packageName$suffix"
            Mode.UNAVAILABLE -> null
        }
    }

    private fun execInternal(command: String, repairCodeCache: Boolean, noFallback: Boolean): String {
        val shellCommand = when (mode) {
            Mode.RUN_AS -> "run-as $packageName sh -c ${shellQuote(command)}"
            Mode.DIRECT_SHELL, Mode.ROOT_DIRECT, Mode.SU_ROOT -> buildDirectCommand(command, repairCodeCache)
            Mode.UNAVAILABLE -> error(unavailableReason ?: "App sandbox is unavailable for $packageName")
        }
        val output = if (noFallback) {
            adb.execAdbShellScriptNoFallback(shellCommand)
        } else {
            adb.execAdbShellScript(shellCommand)
        }
        if (mode == Mode.RUN_AS || !repairCodeCache) {
            return output
        }
        val lines = output.lineSequence().toList()
        val repairStartIndex = lines.indexOfFirst { it.trim() == REPAIR_START_MARKER }
        val repairOutput = if (repairStartIndex >= 0) lines.drop(repairStartIndex + 1) else lines
        if (repairStartIndex < 0 || repairOutput.none { it.trim() == REPAIR_MARKER }) {
            error("Failed to repair code_cache ownership or SELinux context for $packageName: " +
                repairOutput.joinToString("\n"))
        }
        return lines.take(repairStartIndex).dropLastWhile { it.isEmpty() }.joinToString("\n")
    }

    private fun buildDirectCommand(command: String, repairCodeCache: Boolean): String {
        val appDataDir = dataDir ?: error("Unable to resolve dataDir for $packageName")
        val repair = if (repairCodeCache) buildRepairScript() else ""
        val scopedCommand = "cd ${shellQuote(appDataDir)} || exit 1; $repair($command)"
        return when (mode) {
            Mode.SU_ROOT -> buildSuCommand(requireNotNull(resolution.suStyle), scopedCommand)
            else -> "sh -c ${shellQuote(scopedCommand)}"
        }
    }

    private fun buildRepairScript(): String {
        return "owner=\$(stat -c %u:%g .); " +
            "repair_code_cache() { printf '\n$REPAIR_START_MARKER\n'; repair_ok=1; " +
            "if [ -e code_cache ]; then " +
            "probe=code_cache/.jugg_repair_probe_\$\$; touch \"\$probe\" || repair_ok=0; " +
            "if [ \"\$repair_ok\" = 1 ] && [ \"\$(stat -c %u:%g \"\$probe\")\" != \"\$owner\" ]; then " +
            "chown -R \"\$owner\" code_cache || repair_ok=0; fi; " +
            "rm -f \"\$probe\"; " +
            "if [ \"\$repair_ok\" = 1 ] && command -v chcon >/dev/null 2>&1; then " +
            "code_cache_context=\$(ls -Zd code_cache) || repair_ok=0; " +
            "code_cache_context=\${code_cache_context%% *}; " +
            "if [ \"\$repair_ok\" = 1 ]; then chcon -R \"\$code_cache_context\" code_cache || repair_ok=0; fi; " +
            "if [ \"\$repair_ok\" = 1 ]; then " +
            "find code_cache -type f -name '*.so' -exec chcon '$EXECUTABLE_CONTEXT' {} + || repair_ok=0; fi; " +
            "elif [ \"\$repair_ok\" = 1 ] && command -v restorecon >/dev/null 2>&1; then " +
            "restorecon -RF code_cache || repair_ok=0; fi; fi; " +
            "if [ \"\$repair_ok\" = 1 ]; then printf '\n$REPAIR_MARKER\n'; fi; " +
            "}; trap repair_code_cache EXIT; "
    }

    private fun resolve(): Resolution {
        val uid = runAsUid
        if (uid != null && uid in AS_DEPLOYER_UID_RANGE) {
            logger.debug("Apply Changes run-as capability compatible for $packageName: uid=$uid")
            return Resolution(
                mode = Mode.RUN_AS,
                detail = "run-as marker uid=$uid",
            )
        }

        val runAsDetail = when (uid) {
            null -> "success marker or compatible SELinux context missing"
            else -> "uid $uid outside ${AS_DEPLOYER_UID_RANGE.first}..${AS_DEPLOYER_UID_RANGE.last}"
        }
        val appDataDir = resolveDataDir()
            ?: return unavailable("run-as $runAsDetail; PackageManager dataDir unavailable")
        return resolveDirectMode(appDataDir, runAsDetail)
    }

    private fun resolveDirectMode(appDataDir: String, runAsDetail: String): Resolution {
        val directProbeOutput = adb.execAdbShellScript(buildDirectProbe(appDataDir))
        if (hasDirectProbeMarker(directProbeOutput)) {
            logger.debug("Direct app sandbox selected for $packageName: mode=${Mode.DIRECT_SHELL}")
            return directResolution(Mode.DIRECT_SHELL, appDataDir, null, "ordinary shell probe succeeded")
        }

        val shellUid = adb.execAdbShellCmd("id -u").trim()
        val rootProbe = probeRootDirect(appDataDir, shellUid)
        rootProbe.resolution?.let {
            return it
        }
        probeSuDirect(appDataDir)?.let {
            return it
        }

        return unavailable(
            "run-as $runAsDetail; shell uid=${shellUid.ifEmpty { "unknown" }}; " +
                "direct shell probe=${summarize(directProbeOutput)}; root requested=${rootProbe.requested}; " +
                "root reconnected=${rootProbe.reconnected}; root probe=${summarize(rootProbe.output)}; su unavailable",
            appDataDir,
        )
    }

    private fun probeRootDirect(appDataDir: String, shellUid: String): RootProbeResult {
        if (shellUid == "0") {
            return RootProbeResult()
        }
        val reconnected = adb.requestRootAdbd()
        if (!reconnected) {
            return RootProbeResult(requested = true)
        }
        val output = adb.execAdbShellScript(buildDirectProbe(appDataDir))
        val resolution = if (hasDirectProbeMarker(output)) {
            logger.debug("Direct app sandbox selected for $packageName: mode=${Mode.ROOT_DIRECT}")
            directResolution(Mode.ROOT_DIRECT, appDataDir, null, "adb root probe succeeded")
        } else {
            null
        }
        return RootProbeResult(resolution, requested = true, reconnected = true, output = output)
    }

    private fun probeSuDirect(appDataDir: String): Resolution? {
        for (suStyle in SuStyle.entries) {
            val output = adb.execAdbShellScript(buildSuCommand(suStyle, buildScopedProbe(appDataDir)))
            if (hasDirectProbeMarker(output)) {
                logger.debug("Direct app sandbox selected for $packageName: mode=${Mode.SU_ROOT}")
                return directResolution(Mode.SU_ROOT, appDataDir, suStyle, "${suStyle.name} probe succeeded")
            }
        }
        return null
    }

    private fun directResolution(
        mode: Mode,
        appDataDir: String,
        suStyle: SuStyle?,
        detail: String,
    ): Resolution {
        return Resolution(
            mode = mode,
            dataDir = appDataDir,
            suStyle = suStyle,
            detail = detail,
        )
    }

    private fun unavailable(detail: String, appDataDir: String? = null): Resolution {
        logger.debug("Direct app sandbox unavailable for $packageName: $detail")
        return Resolution(
            mode = Mode.UNAVAILABLE,
            dataDir = appDataDir,
            detail = detail,
        )
    }

    private fun buildDirectProbe(appDataDir: String): String {
        return "sh -c ${shellQuote(buildScopedProbe(appDataDir))}"
    }

    private fun buildSuCommand(suStyle: SuStyle, script: String): String {
        return when (suStyle) {
            SuStyle.UID -> "su 0 sh -c ${shellQuote(script)}"
            SuStyle.DEFAULT -> "su -c ${shellQuote(script)}"
            SuStyle.COMMAND -> "su sh -c ${shellQuote(script)}"
        }
    }

    private fun buildScopedProbe(appDataDir: String): String {
        return "cd ${shellQuote(appDataDir)} || exit 1; " +
            "mkdir -p code_cache || exit 2; owner=\$(stat -c %u:%g .) || exit 3; " +
            "probe=code_cache/.jugg_direct_probe_\$\$; " +
            "cleanup() { rm -f \"\$probe\" >/dev/null 2>&1 || true; }; trap cleanup EXIT; " +
            "touch \"\$probe\" || exit 4; actual=\$(stat -c %u:%g \"\$probe\") || exit 5; " +
            "if [ \"\$actual\" != \"\$owner\" ]; then chown \"\$owner\" \"\$probe\" || exit 6; fi; " +
            "if command -v restorecon >/dev/null 2>&1; then restorecon \"\$probe\" || exit 7; fi; " +
            "[ \"\$(stat -c %u:%g \"\$probe\")\" = \"\$owner\" ] || exit 8; " +
            "rm -f \"\$probe\" || exit 9; trap - EXIT; printf \"$DIRECT_MARKER\\n\""
    }

    private fun hasDirectProbeMarker(output: String): Boolean {
        return output.lineSequence().count { it.trim() == DIRECT_MARKER } == 1
    }

    private fun resolveDataDir(): String? {
        val output = adb.execAdbShellCmd("dumpsys package $packageName | grep -m 1 'dataDir='")
        val value = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("dataDir=") }
            ?.substringAfter("dataDir=")
            ?.trim()
        if (value == null || !SAFE_ABSOLUTE_PATH.matches(value) || value.contains("..")) {
            logger.debug("Invalid app dataDir for $packageName: $value")
            return null
        }
        return value
    }

    private fun summarize(output: String): String {
        return output.trim().lineSequence().firstOrNull()?.take(160)?.ifEmpty { "empty" } ?: "empty"
    }

    private fun isSafeRelativePath(path: String): Boolean {
        return path.isNotBlank() && !path.startsWith("/") && !path.contains("..") &&
            SAFE_RELATIVE_PATH.matches(path)
    }

    companion object {
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val SAFE_ABSOLUTE_PATH = Regex("/[A-Za-z0-9_./-]+")
        private val SAFE_RELATIVE_PATH = Regex("[A-Za-z0-9_./-]+")
        private val AS_DEPLOYER_UID_RANGE = 10000..19999
        private const val DIRECT_MARKER = "__JUGG_DIRECT_SANDBOX_OK__"
        private const val REPAIR_START_MARKER = "__JUGG_APP_SANDBOX_REPAIR_START__"
        private const val REPAIR_MARKER = "__JUGG_APP_SANDBOX_REPAIRED__"
        private const val EXECUTABLE_CONTEXT = "u:object_r:apk_data_file:s0"

        private const val RUN_AS_MARKER = "__JUGG_RUN_AS_OK__"
        private val RUN_AS_MARKER_PATTERN = Regex("$RUN_AS_MARKER:(\\d+)")
        private const val RUN_AS_CONTEXT_MARKER = "__JUGG_RUN_AS_CONTEXT__"
        private val RUN_AS_CONTEXT_MARKER_PATTERN = Regex("$RUN_AS_CONTEXT_MARKER:(\\S+)\\|(\\S+)")

        fun probeApplyChangesCapability(
            adb: IDeviceAdb,
            packageName: String,
        ): ApplyChangesCapability {
            require(PACKAGE_NAME_PATTERN.matches(packageName)) { "Unsafe package name: $packageName" }
            val uid = probeCompatibleRunAsUid(adb, packageName)
            return if (uid != null && uid in AS_DEPLOYER_UID_RANGE) {
                ApplyChangesCapability.COMPATIBLE
            } else {
                ApplyChangesCapability.INCOMPATIBLE
            }
        }

        private fun probeCompatibleRunAsUid(adb: IDeviceAdb, packageName: String): Int? {
            val script = "uid=\$(id -u) || exit 1; " +
                "probe=code_cache/.jugg_run_as_probe_\$\$; " +
                "cleanup() { rm -f \"\$probe\" >/dev/null 2>&1 || true; }; trap cleanup EXIT; " +
                "touch \"\$probe\" || exit 2; " +
                "code_cache_context=\$(ls -Zd code_cache) || exit 3; " +
                "code_cache_context=\${code_cache_context%% *}; " +
                "probe_context=\$(ls -Z \"\$probe\") || exit 4; " +
                "probe_context=\${probe_context%% *}; " +
                "rm -f \"\$probe\" || exit 5; trap - EXIT; " +
                "printf \"$RUN_AS_MARKER:%s\\n\" \"\$uid\"; " +
                "printf \"$RUN_AS_CONTEXT_MARKER:%s|%s\\n\" \"\$code_cache_context\" \"\$probe_context\""
            val output = adb.execAdbShellScript("run-as $packageName sh -c ${shellQuote(script)}")
            val uidMatches = output.lineSequence()
                .mapNotNull { RUN_AS_MARKER_PATTERN.matchEntire(it.trim()) }
                .toList()
            val contextMatches = output.lineSequence()
                .mapNotNull { RUN_AS_CONTEXT_MARKER_PATTERN.matchEntire(it.trim()) }
                .toList()
            val contexts = contextMatches.singleOrNull()?.groupValues ?: return null
            if (contexts[1] != contexts[2]) {
                return null
            }
            return uidMatches.singleOrNull()?.groupValues?.get(1)?.toIntOrNull()
        }

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
