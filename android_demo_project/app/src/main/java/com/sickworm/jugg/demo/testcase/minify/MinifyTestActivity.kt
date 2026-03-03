package com.sickworm.jugg.demo.testcase.minify

import android.app.Activity
import android.os.Bundle

/**
 * Activity that references all minify test classes to prevent R8 from removing them.
 * This activity is declared in AndroidManifest.xml.
 */
class MinifyTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Reference all test classes to prevent R8 from removing them
        val keepClassName = KeepClassName()
        keepClassName.obfuscatedField = "test"
        System.out.println(keepClassName.obfuscatedMethod())

        val keepClassMembers = KeepClassMembers()
        keepClassMembers.keptField = "test"
        keepClassMembers.keptMethod()
        keepClassMembers.obfuscatedField = "test"
        keepClassMembers.obfuscatedMethod()

        val fullyObfuscated = FullyObfuscated()
        fullyObfuscated.fieldOne = "test"
        fullyObfuscated.fieldTwo = 42
        fullyObfuscated.methodOne()
        fullyObfuscated.methodTwo("param")
        System.out.println(fullyObfuscated.fieldThree)

        val keepMethodName = KeepMethodName()
        keepMethodName.keptMethod()
        keepMethodName.obfuscatedMethod()

        val keepAnnotated = KeepAnnotated()
        keepAnnotated.keptField = "test"
        keepAnnotated.keptMethod()
        keepAnnotated.obfuscatedField = "test"
        keepAnnotated.obfuscatedMethod()

        val interfaceImpl = InterfaceImplementor()
        interfaceImpl.interfaceMethod("test")
        interfaceImpl.normalMethod()

        val serializableClass = SerializableClass()
        serializableClass.serializedField = "test"
        serializableClass.transientField = "test"

        val enumClass = MinifyTestEnum.VALUE_ONE
        System.out.println(enumClass.enumMethod())

        val innerClassHolder = InnerClassHolder()
        val inner = innerClassHolder.InnerClass()
        inner.innerField = "test"
        inner.innerMethod()
        val staticInner = InnerClassHolder.StaticInnerClass()
        staticInner.staticInnerField = "test"
        staticInner.staticInnerMethod()
        innerClassHolder.usePrivateInner()

        val nativeMethodClass = NativeMethodClass()
        // nativeMethodClass.nativeMethod() // Don't actually call native method
        System.out.println(nativeMethodClass.normalMethod())
        System.out.println(NativeMethodClass().nativeField)

        val wildcardKeep = WildcardKeepClass()
        wildcardKeep.prefixKeptField = "test"
        wildcardKeep.prefixKeptMethod()
        wildcardKeep.otherField = "test"
        wildcardKeep.otherMethod()

        val keepClassAndMembers = KeepClassAndMembers()
        keepClassAndMembers.keptFieldOne = "test"
        keepClassAndMembers.keptFieldTwo = 42
        keepClassAndMembers.keptMethodOne()
        keepClassAndMembers.keptMethodTwo()

        // R8 inline test cases
        com.example.myapplication.r8test.R8InlineTestCases.runAllTests()

        // Note: UnreferencedClass is intentionally NOT referenced here
        // to test that R8 removes it completely
    }
}
