package com.sickworm.jugg.demo.testcase.application

import android.app.Application
import android.content.Context
import android.util.Log
import com.sickworm.jugg.demo.testcase.appcomponentfactory.TestInitialize

class MyApplication: Application() {

    override fun attachBaseContext(base: Context?) {
        Log.i("MyApplication", "attachBaseContext in: $base")
        TestInitialize.application = this
        TestInitialize.initBeforeAttach()
        super.attachBaseContext(base)
        Log.i("MyApplication", "attachBaseContext out")
        TestInitialize.initAfterAttach()
    }

    override fun onCreate() {
        Log.i("MyApplication", "onCreate in")
        super.onCreate()
        Log.i("MyApplication", "onCreate out")
    }
}
