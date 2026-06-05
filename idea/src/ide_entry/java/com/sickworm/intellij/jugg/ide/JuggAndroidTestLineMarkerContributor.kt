package com.sickworm.intellij.jugg.ide

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiElement
import com.intellij.ui.IconManager
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.logger.JuggLogger
import javax.swing.Icon

private const val ANDROID_TEST_PATH_SEGMENT = "/src/androidTest/"
private const val SCAN_SUMMARY_INTERVAL = 500
private const val SCAN_SUMMARY_TOTAL_COST_MS = 100L
private const val SLOW_SCAN_COST_MS = 20L

/**
 * JuggAndroidTestLineMarkerContributor displays a gutter icon on @Test methods and classes
 * that reside under `src/androidTest/`. It is registered twice in plugin.xml – once for Java
 * PSI and once for Kotlin PSI.
 *
 * Behaviour:
 *  - If enableAndroidTest is not configured: shows a Notification directing the user to the
 *    App RunConfig; does NOT auto-modify any config.
 *  - If enableAndroidTest is configured: creates a temporary JuggAndroidTestRunConfiguration
 *    and triggers execution through JuggManager.
 */
class JuggAndroidTestLineMarkerContributor : RunLineMarkerContributor() {

    /**
     * Returns a [RunLineMarkerContributor.Info] when the element is a @Test method/class inside
     * `src/androidTest/`, otherwise returns null.
     */
    override fun getInfo(element: PsiElement): Info? {
        val containingFile = element.containingFile ?: return null

        // Hard constraint: PSI file must be under src/androidTest/
        val filePath = containingFile.virtualFile?.path ?: return null
        if (!isSupportedAndroidTestPath(filePath)) {
            return null
        }

        val scanStartNs = System.nanoTime()
        val annotatedElement = findAnnotatedElement(element)
        logScanEvents(
            element = element,
            filePath = filePath,
            costMs = (System.nanoTime() - scanStartNs) / 1_000_000,
            hasMarker = annotatedElement != null,
        )
        if (annotatedElement == null) {
            return null
        }
        logMarkerHitIfNeeded(element, annotatedElement, filePath)

        return Info(
            lineMarkerIcon(),
            arrayOf(createRunAction(annotatedElement)),
        ) { "Run Android Test with Jugg" }
    }

    private fun findAnnotatedElement(element: PsiElement): PsiElement? {
        val owner = findAnnotatedElementOwner(
            element,
            readNameIdentifier = ::readNameIdentifier,
            ownerParent = { current -> (current as? PsiElement)?.parent },
        ) as? PsiElement ?: return null
        return owner
    }

    private fun logScanEvents(element: PsiElement, filePath: String, costMs: Long, hasMarker: Boolean) {
        val events = recordScanResult(filePath, hasMarker, costMs)
        if (events.isEmpty()) return
        val logger = JuggLogger.getInstance(element.project, "JuggAndroidTestLineMarkerContributor")
        events.forEach { event ->
            when (event) {
                is ScanLogEvent.Summary -> logger.debug(
                    "Jugg androidTest gutter scan summary: " +
                            "reason=${event.reason}, scans=${event.scanCount}, hits=${event.hitCount}, " +
                            "misses=${event.missCount}, totalCost=${event.totalCostMs}ms, " +
                            "maxCost=${event.maxCostMs}ms, path=${event.filePath}",
                )
                is ScanLogEvent.SlowScan -> logger.debug(
                    "Jugg androidTest gutter slow scan: " +
                            "cost=${event.costMs}ms, hasMarker=${event.hasMarker}, path=${event.filePath}",
                )
            }
        }
    }

    private fun logMarkerHitIfNeeded(element: PsiElement, annotatedElement: PsiElement, filePath: String) {
        val target = resolveAndroidTestTarget(
            annotatedElement,
            ownerParent = { current -> (current as? PsiElement)?.parent },
        ).copy(sourcePath = filePath)
        val markerKey = markerKey(target)
        if (!recordMarkerHit(markerKey)) return
        JuggLogger.getInstance(element.project, "JuggAndroidTestLineMarkerContributor")
            .debug(
                "Jugg androidTest gutter marker: " +
                        "scope=${target.toScope()}, class=${target.testClass}, method=${target.testMethod}, " +
                        "displayName=${target.displayName}, sourcePath=${target.sourcePath}",
            )
    }

    private fun createRunAction(annotatedElement: PsiElement): AnAction {
        return object : AnAction("Run Test by Jugg", null, lineMarkerIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = annotatedElement.project
                val appSettings = JuggAndroidTestAppRunConfigurationSelector.firstEnabledAndroidTestSettings(project)
                if (appSettings == null) {
                    JuggLogger.getInstance(project, "JuggAndroidTestLineMarkerContributor")
                        .debug(
                            "Jugg androidTest gutter blocked: reason=enableAndroidTestMissing, " +
                                    "sourcePath=${annotatedElement.containingFile?.virtualFile?.path}",
                        )
                    notifyEnableAndroidTestRequired(project)
                    return
                }

                val runManager = RunManager.getInstance(project)
                val factory = JuggAndroidTestConfigurationType.getInstance().configurationFactories.first()
                val configuration = factory.createTemplateConfiguration(project) as JuggAndroidTestRunConfiguration
                val target = resolveAndroidTestTarget(
                    annotatedElement,
                    ownerParent = { current -> (current as? PsiElement)?.parent },
                ).copy(sourcePath = annotatedElement.containingFile?.virtualFile?.path)
                configuration.name = target.displayName
                configuration.state?.let { options ->
                    applyTargetOptions(options, target, appSettings.name)
                }
                JuggLogger.getInstance(project, "JuggAndroidTestLineMarkerContributor")
                    .debug(
                        "Jugg androidTest gutter run: " +
                                "scope=${target.toScope()}, class=${target.testClass}, method=${target.testMethod}, " +
                                "appRunConfig=${appSettings.name}, sourcePath=${target.sourcePath}",
                    )
                val settings = runManager.createConfiguration(configuration, factory)
                runManager.setTemporaryConfiguration(settings)
                ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
            }
        }
    }

    internal sealed class ScanLogEvent {
        data class Summary(
            val filePath: String,
            val scanCount: Int,
            val hitCount: Int,
            val missCount: Int,
            val totalCostMs: Long,
            val maxCostMs: Long,
            val reason: String,
        ) : ScanLogEvent()

        data class SlowScan(
            val filePath: String,
            val costMs: Long,
            val hasMarker: Boolean,
        ) : ScanLogEvent()
    }

    companion object {
        internal fun isOwnerNameIdentifier(
            element: Any,
            owner: Any,
            readNameIdentifier: (Any) -> Any?,
        ): Boolean {
            return readNameIdentifier(owner) == element
        }

        internal fun isAndroidTestEntryNameIdentifier(
            element: Any,
            owner: Any,
            readNameIdentifier: (Any) -> Any?,
        ): Boolean {
            return isOwnerNameIdentifier(element, owner, readNameIdentifier) &&
                    (hasJUnitTestAnnotation(owner) || containsJUnitTestAnnotation(owner))
        }

        internal fun lineMarkerIcon(
            icon: Icon = IconManager.getInstance().getIcon("/res/icons/run_configuration.svg", JuggInitializer::class.java),
        ): Icon = icon

        internal fun createRunAction(icon: Icon): AnAction {
            return object : AnAction("Run Test by Jugg", null, icon) {
                override fun actionPerformed(e: AnActionEvent) = Unit
            }
        }

        internal fun findAnnotatedElementOwner(
            start: Any,
            readNameIdentifier: (Any) -> Any?,
            ownerParent: (Any) -> Any?,
        ): Any? {
            var current: Any? = start
            while (current != null) {
                if (isOwnerNameIdentifier(start, current, readNameIdentifier)) {
                    return current.takeIf {
                        hasJUnitTestAnnotation(it) || containsJUnitTestAnnotation(it)
                    }
                }
                current = ownerParent(current)
            }
            return null
        }

        private fun containsJUnitTestAnnotation(owner: Any): Boolean {
            return readChildren(owner).any { child ->
                hasJUnitTestAnnotation(child) || containsJUnitTestAnnotation(child)
            }
        }

        internal fun resolveAndroidTestTarget(
            owner: Any,
            ownerParent: (Any) -> Any?,
        ): AndroidTestTarget {
            val containingClassName = readContainingClassName(owner, ownerParent)
            val ownerClassName = readClassName(owner)
            val ownerName = readName(owner)
            val isMethodOwner = hasJUnitTestAnnotation(owner)

            val testClass: String?
            val testMethod: String?
            if (isMethodOwner) {
                // Owner is a test method (has @Test directly) -> run single method
                testClass = containingClassName
                    ?: ownerClassName?.let { fqName ->
                        ownerName?.let { methodName ->
                            if (fqName.endsWith(".$methodName")) fqName.removeSuffix(".$methodName") else fqName
                        }
                    }
                    ?: ownerClassName
                testMethod = ownerName
            } else {
                // Owner is a class (contains @Test methods but doesn't have @Test itself) -> run all methods
                testClass = containingClassName ?: ownerClassName
                testMethod = if (containingClassName != null) ownerName else null
            }
            val displayName = buildRunConfigurationDisplayName(testClass, testMethod)
            return AndroidTestTarget(testClass, testMethod, displayName)
        }

        private fun buildRunConfigurationDisplayName(testClass: String?, testMethod: String?): String {
            testMethod?.takeIf { it.isNotBlank() }?.let { return "$it()" }
            testClass?.takeIf { it.isNotBlank() }?.let { className ->
                return className.substringAfterLast('.').substringAfterLast('$')
            }
            return "Jugg Android Test"
        }

        /**
         * Checks whether a Java or Kotlin PSI owner carries a JUnit test annotation.
         * The helper supports both `getAnnotations()` and `getAnnotationEntries()`.
         */
        internal fun hasJUnitTestAnnotation(owner: Any): Boolean {
            val annotations = readAnnotations(owner) ?: return false
            return annotations.any { annotation ->
                readAnnotationName(annotation)?.let { normalizeAnnotationName(it) } in setOf(
                    "Test",
                    "org.junit.Test",
                    "org.junit.jupiter.api.Test",
                )
            }
        }

        fun isSupportedAndroidTestPath(filePath: String): Boolean {
            val normalized = filePath.replace('\\', '/')
            return normalized.contains(ANDROID_TEST_PATH_SEGMENT)
        }

        private fun readName(owner: Any): String? {
            return readStringByNoArgMethod(owner, "getName")
        }

        private fun readNameIdentifier(owner: Any): Any? {
            return readObjectByNoArgMethod(owner, "getNameIdentifier")
                ?: readObjectByNoArgMethod(owner, "getNameIdentifierKt")
        }

        private fun readContainingClassName(owner: Any, ownerParent: (Any) -> Any?): String? {
            readObjectByNoArgMethod(owner, "getContainingClass")?.let { containingClass ->
                if (containingClass !== owner) {
                    readClassName(containingClass)?.let { return it }
                }
            }

            var current = ownerParent(owner)
            while (current != null) {
                readClassName(current)?.let { return it }
                current = ownerParent(current)
            }
            return null
        }

        private fun readClassName(owner: Any): String? {
            return readStringByNoArgMethod(owner, "getQualifiedName")
                ?: readStringByNoArgMethod(owner, "getFqName")
                ?: readObjectByNoArgMethod(owner, "getFqName")?.let { fqName ->
                    readStringByNoArgMethod(fqName, "asString") ?: fqName.toString()
                }
        }

        private fun readAnnotations(owner: Any): Array<*>? {
            return try {
                val annotations = owner::class.java.methods.filter {
                    it.name == "getAnnotations" || it.name == "getAnnotationEntries"
                }.flatMap { method ->
                    when (val value = method.invoke(owner)) {
                        is Array<*> -> value.asList()
                        is Collection<*> -> value.toList()
                        else -> emptyList()
                    }
                }
                annotations.takeIf { it.isNotEmpty() }?.toTypedArray()
            } catch (_: Exception) {
                null
            }
        }

        private fun readChildren(owner: Any): List<Any> {
            return try {
                val value = owner::class.java.methods.firstOrNull {
                    it.name == "getChildren" && it.parameterCount == 0
                }?.invoke(owner) ?: return emptyList()
                when (value) {
                    is Array<*> -> value.filterNotNull()
                    is Collection<*> -> value.filterNotNull()
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun readAnnotationName(annotation: Any?): String? {
            if (annotation == null) return null
            return try {
                val qnMethod = annotation::class.java.methods.firstOrNull {
                    it.name == "getQualifiedName" || it.name == "getShortName" || it.name == "getText"
                } ?: return null
                val value = qnMethod.invoke(annotation) ?: return null
                if (value is String) return value
                value::class.java.methods.firstOrNull { it.name == "asString" }?.invoke(value) as? String
            } catch (_: Exception) {
                null
            }
        }

        private fun normalizeAnnotationName(rawName: String): String {
            return rawName
                .trim()
                .removePrefix("@")
                .substringBefore("(")
                .substringAfterLast(".")
        }

        private fun readObjectByNoArgMethod(owner: Any, methodName: String): Any? {
            return try {
                owner::class.java.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    ?.invoke(owner)
            } catch (_: Exception) {
                null
            }
        }

        private fun readStringByNoArgMethod(owner: Any, methodName: String): String? {
            return readObjectByNoArgMethod(owner, methodName) as? String
        }

        internal fun applyTargetOptions(
            options: JuggAndroidTestRunConfigurationOptions,
            target: AndroidTestTarget,
            appRunConfigurationName: String,
        ) {
            options.testClass = target.testClass
            options.testMethod = target.testMethod
            options.sourcePath = target.sourcePath
            options.testScope = target.toScope()
            options.appRunConfigurationName = appRunConfigurationName
        }

        internal data class AndroidTestTarget(
            val testClass: String?,
            val testMethod: String?,
            val displayName: String,
            val sourcePath: String? = null,
        ) {
            fun toScope(): AndroidTestScope = if (testMethod == null) AndroidTestScope.CLASS else AndroidTestScope.METHOD
        }

        @Synchronized
        internal fun recordScanResult(filePath: String, hasMarker: Boolean, costMs: Long): List<ScanLogEvent> {
            val events = mutableListOf<ScanLogEvent>()
            val activeFilePath = currentScanFilePath
            if (activeFilePath != null && activeFilePath != filePath) {
                buildCurrentScanSummary("fileChanged")?.let { events.add(it) }
                resetCurrentScanStats(filePath)
            } else if (activeFilePath == null) {
                resetCurrentScanStats(filePath)
            }

            currentScanCount++
            if (hasMarker) currentScanHitCount++ else currentScanMissCount++
            currentScanTotalCostMs += costMs
            currentScanMaxCostMs = maxOf(currentScanMaxCostMs, costMs)

            if (costMs >= SLOW_SCAN_COST_MS) {
                events.add(ScanLogEvent.SlowScan(filePath, costMs, hasMarker))
            }
            if (shouldEmitScanSummary()) {
                buildCurrentScanSummary("threshold")?.let { events.add(it) }
                lastSummaryScanCount = currentScanCount
                lastSummaryTotalCostMs = currentScanTotalCostMs
            }
            return events
        }

        @Synchronized
        internal fun recordMarkerHit(markerKey: String): Boolean {
            if (lastMarkerKey == markerKey) return false
            lastMarkerKey = markerKey
            return true
        }

        @Synchronized
        internal fun resetScanCostLogThrottleForTest() {
            currentScanFilePath = null
            currentScanCount = 0
            currentScanHitCount = 0
            currentScanMissCount = 0
            currentScanTotalCostMs = 0L
            currentScanMaxCostMs = 0L
            lastSummaryScanCount = 0
            lastSummaryTotalCostMs = 0L
            lastMarkerKey = null
        }

        /**
         * Shows the "enableAndroidTest not configured" notification.
         * Called from the gutter icon action when the user clicks without the flag enabled.
         */
        fun notifyEnableAndroidTestRequired(project: com.intellij.openapi.project.Project) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Jugg Notification")
                .createNotification(
                    "Enable Android Test required",
                    "To run Android Instrumentation Tests with Jugg, please enable " +
                            "'Enable incremental Android Test' in the Jugg Run Configuration " +
                            "and run a full Gradle compile once.",
                    NotificationType.INFORMATION,
                )
                .addAction(object : AnAction("Open Run Configuration") {
                    override fun actionPerformed(e: AnActionEvent) {
                        com.intellij.execution.impl.EditConfigurationsDialog(project).show()
                    }
                })
                .notify(project)
        }

        private fun markerKey(target: AndroidTestTarget): String {
            return "${target.toScope()}:${target.testClass.orEmpty()}#${target.testMethod.orEmpty()}"
        }

        private fun shouldEmitScanSummary(): Boolean {
            return currentScanCount - lastSummaryScanCount >= SCAN_SUMMARY_INTERVAL ||
                    currentScanTotalCostMs - lastSummaryTotalCostMs >= SCAN_SUMMARY_TOTAL_COST_MS
        }

        private fun buildCurrentScanSummary(reason: String): ScanLogEvent.Summary? {
            val filePath = currentScanFilePath ?: return null
            if (currentScanCount == lastSummaryScanCount && currentScanTotalCostMs == lastSummaryTotalCostMs) {
                return null
            }
            return ScanLogEvent.Summary(
                filePath = filePath,
                scanCount = currentScanCount,
                hitCount = currentScanHitCount,
                missCount = currentScanMissCount,
                totalCostMs = currentScanTotalCostMs,
                maxCostMs = currentScanMaxCostMs,
                reason = reason,
            )
        }

        private fun resetCurrentScanStats(filePath: String) {
            currentScanFilePath = filePath
            currentScanCount = 0
            currentScanHitCount = 0
            currentScanMissCount = 0
            currentScanTotalCostMs = 0L
            currentScanMaxCostMs = 0L
            lastSummaryScanCount = 0
            lastSummaryTotalCostMs = 0L
        }

        private var currentScanFilePath: String? = null
        private var currentScanCount: Int = 0
        private var currentScanHitCount: Int = 0
        private var currentScanMissCount: Int = 0
        private var currentScanTotalCostMs: Long = 0L
        private var currentScanMaxCostMs: Long = 0L
        private var lastSummaryScanCount: Int = 0
        private var lastSummaryTotalCostMs: Long = 0L
        private var lastMarkerKey: String? = null
    }
}
