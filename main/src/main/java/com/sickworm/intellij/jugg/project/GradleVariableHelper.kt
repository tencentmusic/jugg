package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel
import com.android.tools.idea.gradle.repositories.search.CachingRepositorySearchFactory
import com.android.tools.idea.gradle.structure.configurables.PsContextImpl
import com.android.tools.idea.gradle.structure.model.PsProjectImpl
import com.android.tools.idea.gradle.structure.model.meta.maybeValue
import com.intellij.openapi.project.Project

class GradleVariableHelper {

    private var psContext: PsContextImpl? = null

    fun init(project: Project) {
        val repositorySearchFactory = CachingRepositorySearchFactory()
        psContext = PsContextImpl(
            PsProjectImpl(project, repositorySearchFactory), { },
            disableAnalysis = false,
            disableResolveModels = false,
            cachingRepositorySearchFactory = repositorySearchFactory
        )
    }

    fun readVariable(property: ResolvedPropertyModel, model: GradleBuildModel, isValid: String.() -> Boolean): String? {
        val value = property.valueAsString()?.trim() ?: return null
        return readVariable(value, model, isValid)
    }

    private fun readVariable(value: String, model: GradleBuildModel, isValid: String.() -> Boolean): String? {
        // mostly, variable can be parsed by IDE
        if (value.isValid()) {
            return value
        }

        // parse will failed if variable contains " as "
        var fixedValue = value
        if (fixedValue.contains(" as ")) {
            val index = value.indexOf(" as ")
            fixedValue = value.substring(0, index)
        }

        // after remove " as ", find it in declaredProperties first, which is defined in current build.gradle
        val declaredProperty = model.declaredProperties.find { it.name == fixedValue }
        declaredProperty?.valueAsString()?.let {
            if (it.isValid()) {
                return it
            }
        }

        // if not found, find it in project global properties
        val psProject = psContext?.project ?: return null
        if (!fixedValue.contains('.')) {
            // normal value
            val psVariable = psProject.buildScriptVariables.getVariable(fixedValue)
            psVariable?.value?.maybeValue?.toString()?.let {
                if (it.isValid()) {
                    return it
                }
            }
        } else {
            // value like "globalConfig.compileSdkApi", split it and parse level by level
            val valueList = fixedValue.split('.').toMutableList()
            var psVariable = psProject.buildScriptVariables.getVariable(valueList.first())

            while (valueList.isNotEmpty()) {
                if (valueList.size == 1) {
                    psVariable?.value?.maybeValue?.toString()?.let {
                        if (it.isValid()) {
                            return it
                        } else {
                            return null
                        }
                    }
                }
                if (psVariable?.isMap != true) {
                    return null
                }
                valueList.removeFirst()

                val currentValueName = valueList.first()
                psVariable = psVariable.mapEntries.findElement(currentValueName)
            }
        }


        return null
    }

    fun release() {
        psContext?.dispose()
    }
}