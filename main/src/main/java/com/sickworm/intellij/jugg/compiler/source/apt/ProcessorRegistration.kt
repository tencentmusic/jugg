package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.source.apt.processors.KuiklyPageJuggAptProcessor

object ProcessorRegistration {

    fun get(): List<IJuggAptProcessor> {
        return listOf(
            KuiklyPageJuggAptProcessor(),
        )
    }
}