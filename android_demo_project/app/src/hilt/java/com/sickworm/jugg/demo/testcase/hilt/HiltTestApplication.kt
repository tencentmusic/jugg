package com.sickworm.jugg.demo.testcase.hilt

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Application root for the isolated Hilt incremental compile fixture. */
@HiltAndroidApp
class HiltTestApplication : Application()
