package com.sickworm.intellij.jugg.deploy.flutter

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.logger.getInstance

/**
 * Drops the Flutter extraction cache of one installed app, so the next app start re-extracts
 * `flutter_assets` from the AssetManager overlay.
 *
 * The Flutter Android embedding extracts the JIT runtime files into the app private `app_flutter`
 * directory and skips extraction while `app_flutter/res_timestamp-<versionCode>-<lastUpdateTime>`
 * still matches the installed APK. An overlay update does not change `lastUpdateTime`, so the
 * timestamp has to be removed for the overlay `kernel_blob.bin` to take effect.
 */
class FlutterJitCacheInvalidator(
    private val sandbox: AppSandboxExecutor,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("FlutterJitCacheInvalidator")

    /** Removes the Flutter timestamp; throws when the cache state cannot be verified as cleared. */
    fun invalidate() {
        if (sandbox.mode == AppSandboxExecutor.Mode.UNAVAILABLE) {
            throw IllegalStateException("App sandbox is unavailable" +
                    " (${sandbox.unavailableReason ?: "unknown reason"})," +
                    " cannot invalidate the Flutter JIT extraction cache")
        }
        val output = sandbox.exec(buildInvalidateCommand())
        val resultLine = output.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith(OK_MARKER) || it.startsWith(FAILED_MARKER) }
            ?: throw IllegalStateException("Flutter JIT extraction cache invalidation produced no result:" +
                    " ${output.trim()}")
        if (resultLine.startsWith(FAILED_MARKER)) {
            throw IllegalStateException("Flutter JIT extraction cache invalidation failed," +
                    " timestamp files still present: ${resultLine.removePrefix(FAILED_MARKER).trim()}")
        }
        val removedCount = resultLine.removePrefix(OK_MARKER).trim()
        logger.info("Flutter JIT extraction cache invalidated, removed $removedCount timestamp file(s).")
    }

    /**
     * Deletes only regular files directly inside the fixed `app_flutter` directory whose name starts
     * with the fixed Flutter timestamp prefix, and then proves that none of them survived.
     */
    private fun buildInvalidateCommand(): String {
        return "removed=0; failed=\"\"; " +
            "for file in $APP_FLUTTER_DIR/$TIMESTAMP_PREFIX*; do " +
            "[ -f \"\$file\" ] || continue; " +
            "if rm -f \"\$file\"; then removed=\$((removed + 1)); else failed=\"\$failed \$file\"; fi; " +
            "done; " +
            "remaining=\"\"; " +
            "for file in $APP_FLUTTER_DIR/$TIMESTAMP_PREFIX*; do " +
            "[ -f \"\$file\" ] && remaining=\"\$remaining \$file\"; " +
            "done; " +
            "if [ -n \"\$failed\" ] || [ -n \"\$remaining\" ]; then " +
            "printf \"\\n$FAILED_MARKER%s%s\\n\" \"\$failed\" \"\$remaining\"; exit 1; fi; " +
            "printf \"\\n$OK_MARKER%s\\n\" \"\$removed\""
    }

    companion object {
        private const val APP_FLUTTER_DIR = "app_flutter"
        private const val TIMESTAMP_PREFIX = "res_timestamp-"
        private const val OK_MARKER = "__JUGG_FLUTTER_JIT_CACHE_OK__"
        private const val FAILED_MARKER = "__JUGG_FLUTTER_JIT_CACHE_FAILED__"
    }
}
