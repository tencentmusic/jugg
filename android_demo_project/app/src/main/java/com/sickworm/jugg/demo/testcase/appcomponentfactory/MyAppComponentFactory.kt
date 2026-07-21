package com.sickworm.jugg.demo.testcase.appcomponentfactory

import android.app.Activity
import android.app.AppComponentFactory
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.sickworm.jugg.demo.testcase.appcomponentfactory.TestInitialize.objAfterAttach
import com.sickworm.jugg.demo.testcase.appcomponentfactory.TestInitialize.objBeforeAttach
import com.sickworm.jugg.demo.testcase.appcomponentfactory.TestInitialize.describe

@Suppress("unused")
class MyAppComponentFactory : AppComponentFactory() {

    override fun instantiateClassLoader(cl: ClassLoader, aInfo: ApplicationInfo): ClassLoader {
        Log.i("MyAppComponentFactory", "instantiateClassLoader: $aInfo, classLoader: $cl")
        return super.instantiateClassLoader(cl, aInfo)
    }

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        Log.i("MyAppComponentFactory", "instantiateApplication: $className, classLoader: $cl")
        return super.instantiateApplication(cl, className)
    }

    override fun instantiateActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        Log.i("MyAppComponentFactory", "instantiateActivity: $className, classLoader: $cl")
        initTest()
        return super.instantiateActivity(cl, className, intent)
    }

    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider {
        Log.i(
            "MyAppComponentFactory",
            "instantiateProvider: $className, classLoader: $cl, rawApplication: ${describe(TestInitialize.application)}",
        )
        initTest()
        return super.instantiateProvider(cl, className)
    }

    override fun instantiateReceiver(cl: ClassLoader, className: String, intent: Intent?): BroadcastReceiver {
        Log.i("MyAppComponentFactory", "instantiateReceiver: $className, classLoader: $cl")
        initTest()
        return super.instantiateReceiver(cl, className, intent)
    }

    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service {
        Log.i("MyAppComponentFactory", "instantiateService: $className, classLoader: $cl")
        initTest()
        return super.instantiateService(cl, className, intent)
    }

    private fun initTest() {
        if (objBeforeAttach == null || objAfterAttach == null) {
            Log.e("MyAppComponentFactory", "obj not init! objBeforeAttach: $objBeforeAttach, objAfterAttach: $objAfterAttach")
        } else {
            Log.i("MyAppComponentFactory", "obj init, objBeforeAttach: $objBeforeAttach, objAfterAttach: $objAfterAttach")
        }
    }
}
