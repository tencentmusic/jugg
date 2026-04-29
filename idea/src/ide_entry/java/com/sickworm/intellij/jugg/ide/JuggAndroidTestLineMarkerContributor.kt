package com.sickworm.intellij.jugg.ide

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement

private const val JUNIT_TEST_FQN = "org.junit.Test"
private const val JUNIT5_TEST_FQN = "org.junit.jupiter.api.Test"
private const val ANDROID_TEST_PATH_SEGMENT = "/src/androidTest/"
private const val APP_ANDROID_TEST_PATH_SEGMENT = "/app/src/androidTest/"

/**
 * JuggAndroidTestLineMarkerContributor displays a gutter icon on @Test methods and classes
 * that reside under `src/androidTest/`. It is registered twice in plugin.xml – once for Java
 * PSI and once for Kotlin PSI.
 *
 * Phase 1 behaviour:
 *  - If enableAndroidTest is not configured: shows a Notification directing the user to the
 *    App RunConfig; does NOT auto-modify any config.
 *  - If enableAndroidTest is configured: creates a temporary JuggAndroidTestRunConfiguration
 *    and triggers execution (wired through JuggManager; full wiring is Phase 1 follow-up).
 */
class JuggAndroidTestLineMarkerContributor : RunLineMarkerContributor() {

    private val logger = Logger.getInstance(JuggAndroidTestLineMarkerContributor::class.java)

    /**
     * Returns a [RunLineMarkerContributor.Info] when the element is a @Test method/class inside
     * `src/androidTest/`, otherwise returns null.
     */
    override fun getInfo(element: PsiElement): Info? {
        val containingFile = element.containingFile ?: return null

        // Hard constraint: PSI file must be under src/androidTest/
        val filePath = containingFile.virtualFile?.path ?: return null
        if (!isSupportedAndroidTestPath(filePath)) return null

        val annotatedElement = findAnnotatedElement(element) ?: return null

        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            arrayOf(createRunAction(annotatedElement)),
        ) { "Run Android Test with Jugg" }
    }

    /**
     * Checks whether [element]'s parent (method or class) carries a JUnit @Test annotation.
     * Works for both Java and Kotlin PSI because we use reflection to call getQualifiedName()
     * without a hard dependency on java/kotlin PSI APIs.
     */
    private fun hasJUnitTestAnnotation(element: PsiElement): Boolean {
        return findAnnotatedElement(element) != null
    }

    private fun findAnnotatedElement(element: PsiElement): PsiElement? {
        val parent = element.parent ?: return null
        return try {
            val annotationsMethod = parent::class.java.getMethod("getAnnotations")
            @Suppress("UNCHECKED_CAST")
            val annotations = annotationsMethod.invoke(parent) as? Array<*> ?: return null
            val hasTestAnnotation = annotations.any { anno ->
                val qnMethod = anno!!::class.java.getMethod("getQualifiedName")
                val qn = qnMethod.invoke(anno) as? String ?: return@any false
                qn == JUNIT_TEST_FQN || qn == JUNIT5_TEST_FQN
            }
            if (hasTestAnnotation) parent else null
        } catch (_: Exception) {
            null
        }
    }

    private fun createRunAction(annotatedElement: PsiElement): AnAction {
        return object : AnAction("Run Jugg Android Test") {
            override fun actionPerformed(e: AnActionEvent) {
                val project = annotatedElement.project
                val appOptions = findAppRunConfigurationOptions(project)
                if (appOptions?.enableAndroidTest != true) {
                    notifyEnableAndroidTestRequired(project)
                    return
                }

                val runManager = RunManager.getInstance(project)
                val factory = JuggAndroidTestConfigurationType.getInstance().configurationFactories.first()
                val configuration = factory.createTemplateConfiguration(project) as JuggAndroidTestRunConfiguration
                val target = resolveTarget(annotatedElement)
                configuration.name = target.displayName
                configuration.state?.testClass = target.testClass
                configuration.state?.testMethod = target.testMethod
                val settings = runManager.createConfiguration(configuration, factory)
                runManager.setTemporaryConfiguration(settings)
                ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
            }
        }
    }

    private fun findAppRunConfigurationOptions(project: com.intellij.openapi.project.Project): JuggRunConfigurationOptions? {
        return RunManager.getInstance(project)
            .getConfigurationSettingsList(JuggConfigurationType::class.java)
            .firstOrNull()
            ?.configuration
            ?.let { it as? JuggRunConfiguration }
            ?.state
    }

    private fun resolveTarget(element: PsiElement): AndroidTestTarget {
        val testMethod = readName(element)
        val testClass = readContainingClassName(element)
        val displayName = listOfNotNull(testClass, testMethod).joinToString("#").ifEmpty { "Jugg Android Test" }
        return AndroidTestTarget(testClass, testMethod, displayName)
    }

    private fun readName(element: PsiElement): String? {
        return try {
            element::class.java.getMethod("getName").invoke(element) as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun readContainingClassName(element: PsiElement): String? {
        return try {
            val containingClass = element::class.java.getMethod("getContainingClass").invoke(element) ?: return null
            containingClass::class.java.getMethod("getQualifiedName").invoke(containingClass) as? String
        } catch (_: Exception) {
            null
        }
    }

    private data class AndroidTestTarget(
        val testClass: String?,
        val testMethod: String?,
        val displayName: String,
    )

    companion object {
        fun isSupportedAndroidTestPath(filePath: String): Boolean {
            val normalized = filePath.replace('\\', '/')
            return normalized.contains(APP_ANDROID_TEST_PATH_SEGMENT) &&
                    normalized.contains(ANDROID_TEST_PATH_SEGMENT)
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
    }
}
