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
        logScanCostIfNeeded(
            element = element,
            filePath = filePath,
            costMs = (System.nanoTime() - scanStartNs) / 1_000_000,
            hasMarker = annotatedElement != null,
        )
        if (annotatedElement == null) {
            return null
        }

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

    private fun logScanCostIfNeeded(element: PsiElement, filePath: String, costMs: Long, hasMarker: Boolean) {
        val logState = recordScanCostResult(hasMarker, costMs) ?: return
        JuggLogger.getInstance(element.project, "JuggAndroidTestLineMarkerContributor")
            .debug(
                "Jugg androidTest gutter scan: " +
                        "cost=${logState.currentTotalCostMs}ms, hasMarker=$hasMarker, " +
                        "previousHasMarker=${logState.previousHasMarker}, " +
                        "previousScanCount=${logState.previousScanCount}, " +
                        "previousTotalCost=${logState.previousTotalCostMs}ms, " +
                        "currentScanCount=${logState.currentScanCount}, " +
                        "path=$filePath"
            )
    }

    private fun createRunAction(annotatedElement: PsiElement): AnAction {
        return object : AnAction("Run Test by Jugg", null, lineMarkerIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = annotatedElement.project
                val appSettings = JuggAndroidTestAppRunConfigurationSelector.firstEnabledAndroidTestSettings(project)
                if (appSettings == null) {
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
                val settings = runManager.createConfiguration(configuration, factory)
                runManager.setTemporaryConfiguration(settings)
                ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
            }
        }
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
        internal fun recordScanCostResult(hasMarker: Boolean, costMs: Long): ScanCostLogState? {
            val previousResult = lastScanHasMarker
            if (previousResult == null) {
                lastScanHasMarker = hasMarker
                lastScanResultCount = 1
                lastScanTotalCostMs = costMs
                return ScanCostLogState(null, 0, 0L, hasMarker, lastScanResultCount, lastScanTotalCostMs)
            }
            if (previousResult == hasMarker) {
                lastScanResultCount++
                lastScanTotalCostMs += costMs
                return null
            }
            val previousScanCount = lastScanResultCount
            val previousTotalCostMs = lastScanTotalCostMs
            lastScanHasMarker = hasMarker
            lastScanResultCount = 1
            lastScanTotalCostMs = costMs
            return ScanCostLogState(
                previousResult,
                previousScanCount,
                previousTotalCostMs,
                hasMarker,
                lastScanResultCount,
                lastScanTotalCostMs,
            )
        }

        @Synchronized
        internal fun resetScanCostLogThrottleForTest() {
            lastScanHasMarker = null
            lastScanResultCount = 0
            lastScanTotalCostMs = 0L
        }

        internal data class ScanCostLogState(
            val previousHasMarker: Boolean?,
            val previousScanCount: Int,
            val previousTotalCostMs: Long,
            val currentHasMarker: Boolean,
            val currentScanCount: Int,
            val currentTotalCostMs: Long,
        )

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

        private var lastScanHasMarker: Boolean? = null
        private var lastScanResultCount: Int = 0
        private var lastScanTotalCostMs: Long = 0L
    }
}
