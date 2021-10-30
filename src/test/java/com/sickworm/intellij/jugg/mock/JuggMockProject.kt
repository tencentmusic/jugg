package com.sickworm.intellij.jugg.mock

import com.intellij.execution.RunManager
import com.intellij.mock.MockProject
import com.intellij.mock.MockRunManager

class JuggMockProject: MockProject(null, {}) {

    private val runManager = MockRunManager()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getService(serviceClass: Class<T>): T? {
        if (serviceClass == RunManager::class.java) {
            return runManager as T
        }
        return super.getService(serviceClass)
    }
}