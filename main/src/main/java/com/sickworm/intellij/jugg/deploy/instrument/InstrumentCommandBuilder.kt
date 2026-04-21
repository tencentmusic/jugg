package com.sickworm.intellij.jugg.deploy.instrument

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkInfo

private const val DEFAULT_RUNNER = "androidx.test.runner.AndroidJUnitRunner"

/**
 * InstrumentCommandBuilder assembles the `am instrument` shell command from a [AndroidTestRunSpec]
 * and the test APK metadata.
 *
 * Output format:
 *   am instrument -w -r [-e class <fqn>[#<method>]] [-e <k> <v>]* <testPkg>/<runner>
 */
object InstrumentCommandBuilder {

    private val logger: Logger by lazy { Logger.getInstance(InstrumentCommandBuilder::class.java) }

    fun build(spec: AndroidTestRunSpec, testApk: ApkInfo): String {
        val runner = spec.runnerOverride
            ?: testApk.instrumentationRunner
            ?: run {
                logger.warn("InstrumentCommandBuilder: instrumentationRunner is null in testApk, falling back to $DEFAULT_RUNNER")
                DEFAULT_RUNNER
            }

        return buildString {
            append("am instrument -w -r")

            if (spec.testClass != null) {
                val classArg = if (spec.testMethod != null) {
                    "${spec.testClass}#${spec.testMethod}"
                } else {
                    spec.testClass
                }
                append(" -e class $classArg")
            }

            for ((key, value) in spec.extraArgs) {
                when {
                    value.contains('\'') -> {
                        logger.warn("InstrumentCommandBuilder: extraArg value for key '$key' contains single quote; skipping")
                    }
                    value.contains(' ') -> append(" -e $key '$value'")
                    else -> append(" -e $key $value")
                }
            }

            append(" ${testApk.applicationId}/$runner")
        }
    }
}
