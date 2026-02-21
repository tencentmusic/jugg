package com.sickworm.intellij.jugg.compile.databinding

import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class DataBindingCompileFallbackTest : DataBindingCompileTest() {

    @Before
    fun setUp2() {
        DataBindingArgsManager.isForceUseAptInTest = true
    }

    @After
    fun tearDown2() {
        DataBindingArgsManager.isForceUseAptInTest = null
    }

    override fun assertFallback() {
        assertTrue(DataBindingArgsManager.isKaAptRetryAptSuccess)
    }

    @Test
    override fun testDataBinding() {
        super.testDataBinding()
    }

    override fun checkOutputFiles(compileResult: CompileResult, expect: List<String>) {
        super.checkOutputFiles(compileResult, expect.map {
            it.replace("DataBindingInfo.kt", "DataBindingInfo.java")
        })
    }
}
