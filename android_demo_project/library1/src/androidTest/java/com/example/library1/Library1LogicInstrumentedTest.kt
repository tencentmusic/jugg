package com.example.library1

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.sickworm.jugg.demo.testcase.databinding.library1.DataBindingJavaDemoActivityLibrary1
import com.sickworm.jugg.demo.testcase.databinding.library1.DataBindingKotlinDemoActivityLibrary1
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Library1LogicInstrumentedTest {
    @Test
    fun targetContextUsesHostAppPackage() {
        Log.i("Library1LogicInstrumentedTest", "[targetContextUsesHostAppPackage] in")
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("com.example.library1.test", context.packageName)
    }

    @Test
    fun demoUsersKeepExpectedValues() {
        Log.i("Library1LogicInstrumentedTest", "[demoUsersKeepExpectedValues] in")
        val javaUser = DataBindingJavaDemoActivityLibrary1.User("Jugg User", 25)
        val kotlinUser = DataBindingKotlinDemoActivityLibrary1.User("John", 44)

        assertEquals("Jugg User", javaUser.name)
        assertEquals(44, kotlinUser.age)
    }
}
