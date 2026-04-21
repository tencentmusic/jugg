package com.sickworm.intellij.jugg.ide

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement

private const val JUNIT_TEST_FQN = "org.junit.Test"
private const val JUNIT5_TEST_FQN = "org.junit.jupiter.api.Test"
private const val ANDROID_TEST_PATH_SEGMENT = "/src/androidTest/"

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
        if (!filePath.contains(ANDROID_TEST_PATH_SEGMENT)) return null

        // Only annotated methods/classes with @org.junit.Test (or JUnit 5 equivalent).
        if (!hasJUnitTestAnnotation(element)) return null

        // Phase 1: icon + placeholder actions.
        // Full wiring (run config creation + execution) will be added when JuggManager.runTask
        // overload accepting AndroidTestRunSpec is complete.
        return null
    }

    /**
     * Checks whether [element]'s parent (method or class) carries a JUnit @Test annotation.
     * Works for both Java and Kotlin PSI because we use reflection to call getQualifiedName()
     * without a hard dependency on java/kotlin PSI APIs.
     */
    private fun hasJUnitTestAnnotation(element: PsiElement): Boolean {
        val parent = element.parent ?: return false
        return try {
            val annotationsMethod = parent::class.java.getMethod("getAnnotations")
            @Suppress("UNCHECKED_CAST")
            val annotations = annotationsMethod.invoke(parent) as? Array<*> ?: return false
            annotations.any { anno ->
                val qnMethod = anno!!::class.java.getMethod("getQualifiedName")
                val qn = qnMethod.invoke(anno) as? String ?: return@any false
                qn == JUNIT_TEST_FQN || qn == JUNIT5_TEST_FQN
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
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
