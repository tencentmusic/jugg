package com.sickworm.jugg.demo.testcase.applicationcontext

import android.app.Application

/**
 * Unwraps the original Application held by Jugg BootstrapApplication.
 */
object BootstrapApplicationHelper {

    private const val BOOTSTRAP_APPLICATION = "com.sickworm.intellij.jugg.hotfix.BootstrapApplication"
    private const val RAW_APPLICATION_FIELD = "rawApplication"

    fun unwrap(application: Application?): Application? {
        if (application == null || application.javaClass.name != BOOTSTRAP_APPLICATION) {
            return application
        }

        return runCatching {
            val field = application.javaClass.getDeclaredField(RAW_APPLICATION_FIELD)
            field.isAccessible = true
            field.get(application) as? Application
        }.getOrNull() ?: application
    }
}
